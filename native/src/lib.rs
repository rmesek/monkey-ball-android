//! The game on Android: `android_main`, which GameActivity runs on a thread of its own once it has loaded
//! this library, and the Android `Platform` of the engine's driver (`mb-driver`). The event loop, the
//! surface, touch and the frame are the driver's; this crate adds where the data lives, the document picker
//! (in Kotlin, `MainActivity`, reached through JNI), sound paused in the background and logging to logcat.
//!
//! The model is the desktop's (`monkey-ball-rs/crates/mb-desktop/src/lib.rs`): the first start imports the
//! files the game uses out of the player's disc image into `game.pack` in the app's internal storage, and
//! later starts open that pack and never ask for the image again.

// Everything here needs Android; on the host the crate is empty, so `cargo check` there still passes.
#![cfg(target_os = "android")]

use std::ffi::{CString, c_char, c_int};
use std::fs::File;
use std::os::fd::FromRawFd;
use std::path::Path;
use std::rc::Rc;
use std::sync::Mutex;

use jni::objects::JObject;
use jni::{JNIEnv, JavaVM};
use mb_app::audio::Audio;
use mb_app::content::{Content, LatePack, open_pack_file};
use mb_app::game::Game;
use mb_app::host::{Caps, Command, Host};
use mb_app::import::Importer;
use mb_app::import_screen::{DiscImage, ImageSlot, ImportScreen};
use mb_app::input::InputState;
use mb_app::menu::Menu;
use mb_assets::disc::{DiscStream, FileStream};
use mb_driver::Platform;
use mb_game::{FileStorage, SlotStorage};
use winit::event_loop::EventLoop;
use winit::platform::android::EventLoopBuilderExtAndroid;
use winit::platform::android::activity::AndroidApp;

/// The imported game data, in the app's internal files directory next to the saves.
const PACK_FILE: &str = "game.pack";

// logcat, through the NDK's liblog (always on the device; no crate needed). The tag is what to filter on.
const LOG_INFO: c_int = 4;
const LOG_ERROR: c_int = 6;

#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

fn log(prio: c_int, msg: &str) {
    let text = CString::new(msg.replace('\0', " ")).unwrap_or_default();
    // SAFETY: both strings are NUL-terminated and outlive the call.
    unsafe {
        __android_log_write(prio, c"monkeyball".as_ptr(), text.as_ptr());
    }
}

unsafe extern "C" {
    /// Bionic's system property lookup; `value` must hold PROP_VALUE_MAX (92) bytes.
    fn __system_property_get(name: *const c_char, value: *mut c_char) -> c_int;
}

/// A system property, `None` when it is not set. Debug properties (`debug.*`) can be set from a
/// computer: `adb shell setprop debug.monkeyball.backend gl`.
fn system_property(name: &std::ffi::CStr) -> Option<String> {
    let mut value = [0 as c_char; 92];
    // SAFETY: the buffer has the size bionic writes at most, NUL included.
    let len = unsafe { __system_property_get(name.as_ptr(), value.as_mut_ptr()) };
    // SAFETY: bionic NUL-terminates what it wrote.
    (len > 0).then(|| unsafe { std::ffi::CStr::from_ptr(value.as_ptr()) }.to_string_lossy().into_owned())
}

/// The one graphics backend to run on. wgpu with several backends at once makes a surface for each, and on
/// Android Vulkan and EGL cannot both connect to the same window: the second fails ("already connected")
/// and the surface is invalid. So: Vulkan when the device has a Vulkan adapter, GLES otherwise.
/// `debug.monkeyball.backend` (`vulkan` or `gl`) overrides the choice, for tests and comparisons.
fn choose_backend() -> wgpu::Backends {
    match system_property(c"debug.monkeyball.backend").as_deref() {
        Some("vulkan") => return wgpu::Backends::VULKAN,
        Some("gl") => return wgpu::Backends::GL,
        _ => {}
    }
    let instance = wgpu::Instance::new(mb_app::gpu::instance_descriptor(Some(wgpu::Backends::VULKAN)));
    let adapters = pollster::block_on(instance.enumerate_adapters(wgpu::Backends::VULKAN));
    if adapters.is_empty() {
        wgpu::Backends::GL
    } else {
        wgpu::Backends::VULKAN
    }
}

/// What the document picker returned that the game thread has not taken yet: a file descriptor this
/// library now owns, or -1 when the player cancelled. Written on the UI thread, read in `poll_input`.
static PICKED: Mutex<Option<i32>> = Mutex::new(None);

/// `MainActivity.nativeImagePicked(fd)`, on the UI thread.
#[unsafe(no_mangle)]
extern "system" fn Java_io_github_rmesek_monkeyball_MainActivity_nativeImagePicked(
    _env: JNIEnv,
    _this: JObject,
    fd: jni::sys::jint,
) {
    *PICKED.lock().unwrap_or_else(|e| e.into_inner()) = Some(fd);
}

/// Call a `void method()` of the activity (`MainActivity`) from the game thread.
fn call_activity(app: &AndroidApp, method: &str) -> jni::errors::Result<()> {
    // SAFETY: android-activity keeps the VM and a global reference to the activity valid while
    // `android_main` runs, which is the only time the game thread exists.
    let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) }?;
    let activity = unsafe { JObject::from_raw(app.activity_as_ptr().cast()) };
    let mut env = vm.attach_current_thread()?;
    env.call_method(&activity, method, "()V", &[])?;
    Ok(())
}

/// Open the picked file as a disc image. `Importer::open` also checks that it is the game.
fn open_image(fd: i32) -> Result<DiscImage, String> {
    // SAFETY: the descriptor was detached from its ParcelFileDescriptor for this library, which owns it
    // from here; the File closes it.
    let file = unsafe { File::from_raw_fd(fd) };
    let stream: Box<dyn DiscStream + Send> = Box::new(FileStream::from(file));
    Importer::open(stream).map_err(|e| e.to_string())
}

/// The pack of an earlier import, if it is there and complete; a broken one is removed and imported again.
fn stored_pack(pack: &Path) -> Option<(Content, String, u64)> {
    if !pack.exists() {
        return None;
    }
    match open_pack_file(pack) {
        Ok(found) => Some(found),
        Err(e) => {
            log(LOG_ERROR, &format!("the stored game data cannot be used ({e}); importing again"));
            let _ = std::fs::remove_file(pack);
            None
        }
    }
}

/// The options screen's lines on what is kept on the device.
fn info(pack_len: Option<u64>) -> Vec<String> {
    let mut lines: Vec<String> = (pack_len.into_iter())
        .map(|len| format!("Game data: {} MB", len >> 20))
        .collect();
    lines.push("Kept with the saves in the app's storage".into());
    lines
}

struct Android {
    app: AndroidApp,
    audio: Audio,
    /// Where the import screen, if it runs, takes the image the player picks.
    images: Option<ImageSlot>,
    /// The player chose Options -> Remove game data; the pack is deleted once the run has ended.
    remove_data: bool,
    exit: bool,
    /// The graphics backend, chosen once before the driver starts (`choose_backend`).
    backends: wgpu::Backends,
}

impl Android {
    fn start_picker(&mut self) {
        if let Err(e) = call_activity(&self.app, "pickImage") {
            log(LOG_ERROR, &format!("cannot open the document picker: {e}"));
            self.exit = true;
        }
    }
}

impl Platform for Android {
    fn poll_input(&mut self, _input: &mut InputState, host: &mut Host) {
        let picked = PICKED.lock().unwrap_or_else(|e| e.into_inner()).take();
        match picked {
            // Cancelled: without an image there is nothing to play (as on the desktop).
            Some(fd) if fd < 0 => self.exit = true,
            Some(fd) => match (&self.images, open_image(fd)) {
                (Some(slot), Ok(image)) => slot.offer(image),
                (None, Ok(_)) => {}
                (_, Err(e)) => {
                    // TODO(etap 2): tell the player (a toast) before asking again.
                    log(LOG_ERROR, &format!("not a usable disc image: {e}"));
                    self.start_picker();
                }
            },
            None => {}
        }
        for command in host.drain() {
            match command {
                Command::PickImage => self.start_picker(),
                Command::RemoveGameData => {
                    self.remove_data = true;
                    self.exit = true;
                }
                Command::GameDataReady { bytes } => {
                    host.info.insert(0, format!("Game data: {} MB", bytes >> 20));
                }
                _ => {}
            }
        }
    }

    fn backends(&mut self) -> wgpu::Backends {
        self.backends
    }

    fn wants_exit(&mut self) -> bool {
        self.exit
    }

    fn on_visible(&mut self, visible: bool) {
        // In the background: no sound and no mixing until the game is back.
        self.audio.set_suspended(!visible);
    }

    fn on_ready(&mut self, gpu: &str) {
        log(LOG_INFO, &format!("adapter: {gpu}"));
    }

    fn restart_hint(&self) -> &'static str {
        "Open the game again to continue"
    }

    fn on_error(&mut self, msg: &str) {
        log(LOG_ERROR, &format!("error: {msg}"));
    }
}

fn run(app: AndroidApp) -> Result<(), String> {
    let dir = app
        .internal_data_path()
        .ok_or("the app has no internal data directory")?;
    let event_loop = EventLoop::builder()
        .with_android_app(app.clone())
        .build()
        .map_err(|e| format!("event loop: {e}"))?;
    let audio = Audio::new(Box::new(mb_app::audio_cpal::CpalBackend::new()));
    let menu: Box<dyn Game> =
        Box::new(Menu::new(Box::new(SlotStorage::new(FileStorage::new(&dir)))).with_audio(audio.clone()));
    // The system closes apps: no "Quit" on the title. The game data can be removed from the options.
    let mut host = Host::new(
        Caps {
            remove_data: true,
            ..Caps::default()
        },
        Vec::new(),
    );
    let pack = dir.join(PACK_FILE);
    let mut images = None;
    let (content, initial) = match stored_pack(&pack) {
        Some((content, _, len)) => {
            host.info = info(Some(len));
            (content, menu)
        }
        None => {
            // First start: the import screen asks for the image (Command::PickImage) and fills the pack.
            host.info = info(None);
            let data = Rc::new(LatePack::default());
            let slot = ImageSlot::default();
            images = Some(slot.clone());
            let screen: Box<dyn Game> = Box::new(ImportScreen::new(pack.clone(), data.clone(), slot, menu));
            (Content::new(data), screen)
        }
    };
    let platform = Android {
        app,
        audio,
        images,
        remove_data: false,
        exit: false,
        backends: choose_backend(),
    };
    log(LOG_INFO, &format!("backend: {:?}", platform.backends));
    let platform = mb_driver::run(event_loop, platform, host, content, initial)?;
    if platform.remove_data {
        // The run has ended, so nothing has the pack open any more.
        match std::fs::remove_file(&pack) {
            Ok(()) => log(LOG_INFO, "game data removed; the disc image is asked for at the next start"),
            Err(e) => log(LOG_ERROR, &format!("could not remove the game data: {e}")),
        }
    }
    Ok(())
}

#[unsafe(no_mangle)]
fn android_main(app: AndroidApp) {
    std::panic::set_hook(Box::new(|info| log(LOG_ERROR, &format!("panic: {info}"))));
    log(LOG_INFO, concat!("monkeyball ", env!("CARGO_PKG_VERSION")));
    if let Err(e) = run(app) {
        log(LOG_ERROR, &format!("error: {e}"));
    }
    // One game per process: winit refuses a second event loop, and the engine installs its trig tables
    // once. Android keeps a process after its activity is gone, so end it here and let the next start be
    // a fresh one.
    std::process::exit(0);
}
