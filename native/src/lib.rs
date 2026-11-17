//! The game on Android: `android_main`, which GameActivity runs on a thread of its own once it has loaded
//! this library, and the Android `Platform` of the engine's driver (`mb-driver`). The event loop, the
//! surface, touch and the frame are the driver's; this crate adds where the data lives, the document picker
//! and the tilt sensor (in Kotlin, `MainActivity`, reached through JNI), sound paused in the background and
//! logging to logcat.
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
use std::sync::{Mutex, MutexGuard};
use std::time::{Duration, Instant};

use jni::objects::JObject;
use jni::{JNIEnv, JavaVM};
use mb_app::audio::Audio;
use mb_app::content::{Content, LatePack, open_pack_file};
use mb_app::game::Game;
use mb_app::host::{Caps, Command, Host};
use mb_app::import::Importer;
use mb_app::import_screen::{DiscImage, ImageSlot, ImportScreen};
use mb_app::input::{Action, InputState};
use mb_app::menu::Menu;
use mb_app::perf::{FrameSample, PerfStats};
use mb_app::tilt::TiltSteering;
use mb_app::ui::Insets;
use mb_assets::disc::{DiscStream, FileStream};
use mb_driver::Platform;
use mb_game::progress::PROFILE_KEY;
use mb_game::storage::Storage;
use mb_game::{FileStorage, Profile, SlotStorage};
use winit::event_loop::EventLoop;
use winit::platform::android::EventLoopBuilderExtAndroid;
use winit::platform::android::activity::AndroidApp;
use winit::window::Window;

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
    (len > 0).then(|| {
        unsafe { std::ffi::CStr::from_ptr(value.as_ptr()) }
            .to_string_lossy()
            .into_owned()
    })
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
    let instance = wgpu::Instance::new(mb_app::gpu::instance_descriptor(Some(
        wgpu::Backends::VULKAN,
    )));
    let adapters = pollster::block_on(instance.enumerate_adapters(wgpu::Backends::VULKAN));
    if adapters.is_empty() {
        wgpu::Backends::GL
    } else {
        wgpu::Backends::VULKAN
    }
}

// From Kotlin to Rust. The activity calls the `native*` functions below on the UI thread; each one only
// leaves its news in the inbox, and the game thread reads it once per frame (`poll_input`, `insets`).

/// What the activity has sent that the game thread has not taken yet.
struct Inbox {
    /// The document picker's answer: a file descriptor this library now owns, or -1 when the player
    /// canceled.
    picked: Option<i32>,
    /// The system "back" (gesture or button), sent while the game has somewhere to go back to
    /// (`Game::nav_depth` above 0): one press of Cancel.
    back: bool,
    /// The latest orientation sample while tilt steers: W3C `deviceorientation` beta and gamma in degrees
    /// and the screen's rotation (0, 90, 180, 270).
    tilt: Option<(f32, f32, i32)>,
    /// Space covered by system bars and cutouts (left, top, right, bottom), in window pixels. Not taken:
    /// it stays until the activity sends new values.
    insets: [f32; 4],
}

static INBOX: Mutex<Inbox> = Mutex::new(Inbox {
    picked: None,
    back: false,
    tilt: None,
    insets: [0.0; 4],
});

fn inbox() -> MutexGuard<'static, Inbox> {
    INBOX.lock().unwrap_or_else(|e| e.into_inner())
}

/// `MainActivity.nativeImagePicked(fd)`.
#[unsafe(no_mangle)]
extern "system" fn Java_io_github_rmesek_monkeyball_MainActivity_nativeImagePicked(
    _env: JNIEnv,
    _this: JObject,
    fd: jni::sys::jint,
) {
    inbox().picked = Some(fd);
}

/// `MainActivity.nativeInsets(left, top, right, bottom)`.
#[unsafe(no_mangle)]
extern "system" fn Java_io_github_rmesek_monkeyball_MainActivity_nativeInsets(
    _env: JNIEnv,
    _this: JObject,
    left: jni::sys::jint,
    top: jni::sys::jint,
    right: jni::sys::jint,
    bottom: jni::sys::jint,
) {
    inbox().insets = [left, top, right, bottom].map(|px| px.max(0) as f32);
}

/// `MainActivity.nativeBack()`.
#[unsafe(no_mangle)]
extern "system" fn Java_io_github_rmesek_monkeyball_MainActivity_nativeBack(
    _env: JNIEnv,
    _this: JObject,
) {
    inbox().back = true;
}

/// `MainActivity.nativeTilt(beta, gamma, screenAngle)`.
#[unsafe(no_mangle)]
extern "system" fn Java_io_github_rmesek_monkeyball_MainActivity_nativeTilt(
    _env: JNIEnv,
    _this: JObject,
    beta: jni::sys::jfloat,
    gamma: jni::sys::jfloat,
    screen_angle: jni::sys::jint,
) {
    inbox().tilt = Some((beta, gamma, screen_angle));
}

// From Rust to Kotlin: the game thread calls public methods of `MainActivity` by name.

/// Run `call` with a JNI environment and the activity (`MainActivity`), on the game thread.
fn with_activity<T>(
    app: &AndroidApp,
    call: impl FnOnce(&mut JNIEnv, &JObject) -> jni::errors::Result<T>,
) -> jni::errors::Result<T> {
    // SAFETY: android-activity keeps the VM and a global reference to the activity valid while
    // `android_main` runs, which is the only time the game thread exists.
    let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) }?;
    let activity = unsafe { JObject::from_raw(app.activity_as_ptr().cast()) };
    let mut env = vm.attach_current_thread()?;
    call(&mut env, &activity)
}

/// The argument of a `void` method of the activity; it also gives the method's JNI signature.
enum Arg<'a> {
    None,
    Int(i32),
    Bool(bool),
    Text(&'a str),
}

/// Call `void MainActivity.<method>(arg)`. A failure is logged and only costs that call; `false` then.
fn call(app: &AndroidApp, method: &str, arg: Arg) -> bool {
    let result = with_activity(app, |env, activity| {
        match arg {
            Arg::None => env.call_method(activity, method, "()V", &[]),
            Arg::Int(v) => env.call_method(activity, method, "(I)V", &[v.into()]),
            Arg::Bool(v) => env.call_method(activity, method, "(Z)V", &[v.into()]),
            Arg::Text(text) => {
                // The game thread never returns to Java, so nothing frees its local references for it:
                // this one is deleted when `text` goes out of scope.
                let text = env.auto_local(env.new_string(text)?);
                env.call_method(activity, method, "(Ljava/lang/String;)V", &[(&*text).into()])
            }
        }
        .map(drop)
    });
    if let Err(e) = &result {
        log(
            LOG_ERROR,
            &format!("cannot call MainActivity.{method}: {e}"),
        );
    }
    result.is_ok()
}

/// `MainActivity.hasTiltSensor()`: whether the device reports its orientation, so tilt can steer
/// (`Caps::tilt`, which shows the Controls row in the options). The one call that returns something.
fn has_tilt_sensor(app: &AndroidApp) -> bool {
    let call = with_activity(app, |env, activity| {
        env.call_method(activity, "hasTiltSensor", "()Z", &[])?.z()
    });
    call.unwrap_or_else(|e| {
        log(
            LOG_ERROR,
            &format!("cannot call MainActivity.hasTiltSensor: {e}"),
        );
        false
    })
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
            log(
                LOG_ERROR,
                &format!("the stored game data cannot be used ({e}); importing again"),
            );
            let _ = std::fs::remove_file(pack);
            None
        }
    }
}

/// Full music and sound volume on a phone, where the system's volume keys are the usual control (the engine
/// starts at 80 for the desktop). Written once, as the first save, when there is none yet: what the player
/// changes in Options afterwards is kept.
fn first_settings(storage: &mut dyn Storage) {
    if !matches!(storage.read(PROFILE_KEY), Ok(None)) {
        return;
    }
    let mut profile = Profile::default();
    profile.settings.music_volume = 100;
    profile.settings.sfx_volume = 100;
    if let Err(e) = profile.save(storage) {
        log(LOG_ERROR, &format!("cannot write the first settings: {e}"));
    }
}

/// The first of the options screen's lines on what is kept on the device, once there is game data.
fn game_data_line(bytes: u64) -> String {
    format!("Game data: {} MB", bytes >> 20)
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
    /// The name of the game state on top when the activity was last told (`import`, `menu`, `play`...).
    top: &'static str,
    /// `Game::nav_depth` of the state on top, as the activity was last told.
    nav_depth: u32,
    /// Cancel is held for one frame after a system "back"; released at the next `poll_input`.
    back_down: bool,
    /// Steering by device tilt: on while the player chose it in the options, fed by the activity's
    /// orientation sensor (`nativeTilt`).
    tilt: TiltSteering,
    /// Tilt steers now: chosen, and a stage runs. The activity listens to the sensor and keeps the
    /// rotation exactly while this is on (`MainActivity.setTilt`).
    tilt_steering: bool,
    /// Frame timings of play, summed up every `PERF_PERIOD`: logcat `perf:` lines and the on-screen metrics.
    perf: PerfStats,
    perf_since: Option<Instant>,
    /// The GPU adapter's description (`on_ready`), shown with the frame statistics.
    gpu: String,
}

/// How often the frame timings of play go to logcat.
const PERF_PERIOD: Duration = Duration::from_secs(5);

impl Android {
    fn start_picker(&mut self) {
        // Without the picker there is no way to get an image: end the game.
        if !call(&self.app, "pickImage", Arg::None) {
            self.exit = true;
        }
    }
}

impl Platform for Android {
    fn poll_input(&mut self, input: &mut InputState, host: &mut Host) {
        if self.back_down {
            input.set_action(Action::Cancel, false);
            self.back_down = false;
        }
        // Take the news out of the inbox first: the calls into the activity below must not hold its lock.
        let (back, picked, tilt) = {
            let mut inbox = inbox();
            (
                std::mem::take(&mut inbox.back),
                inbox.picked.take(),
                inbox.tilt.take(),
            )
        };
        if back {
            input.set_action(Action::Cancel, true);
            self.back_down = true;
        }
        match picked {
            // Canceled: without an image there is nothing to play (as on the desktop).
            Some(fd) if fd < 0 => self.exit = true,
            Some(fd) => match (&self.images, open_image(fd)) {
                (Some(slot), Ok(image)) => slot.offer(image),
                (None, Ok(_)) => {}
                (_, Err(e)) => {
                    // The import screen has no fonts before it has an image, so the activity says it, in
                    // the dialog before the picker opens again.
                    log(LOG_ERROR, &format!("not a usable disc image: {e}"));
                    call(&self.app, "onImageRefused", Arg::Text(&e));
                    self.start_picker();
                }
            },
            None => {}
        }
        if let Some((beta, gamma, angle)) = tilt {
            self.tilt.sample(beta, gamma, angle);
        }
        for command in host.drain() {
            match command {
                Command::PickImage => self.start_picker(),
                Command::RemoveGameData => {
                    self.remove_data = true;
                    self.exit = true;
                }
                Command::GameDataReady { bytes } => host.info.insert(0, game_data_line(bytes)),
                // The menu says how the player steers at its start and whenever the option changes.
                // The sensor and the rotation lock follow in `after_update`, once a stage runs.
                Command::Tilt { enabled, range_deg } => {
                    if enabled != self.tilt.enabled() {
                        log(LOG_INFO, &format!("tilt steering chosen: {enabled}"));
                    }
                    self.tilt.set_enabled(enabled, range_deg);
                }
                // A stage starts (or the player taps the recentre button): the pose held now is level.
                Command::CalibrateTilt => self.tilt.calibrate(),
                // The system bars are hidden for good (MainActivity): no fullscreen option.
                Command::SetFullscreen(_) => {}
            }
        }
        self.tilt.apply(input);
    }

    fn insets(&mut self, _window: &Window) -> Insets {
        // The surface is the window at full resolution (the default render scale), so window pixels are
        // surface pixels.
        let [left, top, right, bottom] = inbox().insets;
        Insets {
            left,
            top,
            right,
            bottom,
        }
    }

    fn backends(&mut self) -> wgpu::Backends {
        self.backends
    }

    fn after_update(&mut self, _window: &Window, top: &dyn Game) {
        if top.name() != self.top {
            self.top = top.name();
            log(LOG_INFO, &format!("state: {}", self.top));
            call(&self.app, "onGameState", Arg::Text(self.top));
        }
        if top.nav_depth() != self.nav_depth {
            self.nav_depth = top.nav_depth();
            let depth = self.nav_depth.min(i32::MAX as u32) as i32;
            call(&self.app, "onNavDepth", Arg::Int(depth));
        }
        // Tilt steers only in a stage. The menus leave the sensor off and the screen free to rotate, so the
        // player can turn the phone to the orientation they want to play in before a stage starts.
        let steering = self.tilt.enabled() && self.top == "play";
        if steering != self.tilt_steering {
            self.tilt_steering = steering;
            log(LOG_INFO, &format!("tilt steering: {steering}"));
            call(&self.app, "setTilt", Arg::Bool(steering));
        }
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
        self.gpu = gpu.to_owned();
        call(&self.app, "onGameReady", Arg::Text(gpu));
    }

    fn on_frame(&mut self, sample: &FrameSample) {
        self.perf.record(*sample);
        let since = *self.perf_since.get_or_insert_with(Instant::now);
        if since.elapsed() < PERF_PERIOD {
            return;
        }
        let s = self.perf.summary();
        let lines = [
            format!("{:.1} fps", 1000.0 / s.avg_ms.max(0.001)),
            format!(
                "frame p50 {:.1} p99 {:.1} worst {:.1} ms",
                s.p50_ms, s.p99_ms, s.worst_ms
            ),
            format!(
                "update {:.2} render {:.2} ms avg, {} draws",
                s.update_avg_ms, s.render_avg_ms, s.draws
            ),
        ];
        // One line in logcat (on the emulator), several on the phone's screen when metrics are on
        // (`MainActivity.onPerf`, Metrics.kt), with the GPU the numbers are from.
        log(
            LOG_INFO,
            &format!("perf: {} frames, {}", s.frames, lines.join(", ")),
        );
        let screen = format!("{}\n{}", lines.join("\n"), self.gpu);
        call(&self.app, "onPerf", Arg::Text(&screen));
        self.perf.reset();
        self.perf_since = None;
    }

    fn restart_hint(&self) -> &'static str {
        "Open the game again to continue"
    }

    fn on_error(&mut self, msg: &str) {
        log(LOG_ERROR, &format!("error: {msg}"));
        call(&self.app, "onGameError", Arg::Text(msg));
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
    let mut storage = SlotStorage::new(FileStorage::new(&dir));
    first_settings(&mut storage);
    let menu: Box<dyn Game> = Box::new(Menu::new(Box::new(storage)).with_audio(audio.clone()));
    // The system closes apps: no "Quit" on the title. The game data can be removed from the options.
    // The options' lines on what is kept on the device: the game data's size comes first once there is any.
    let mut host = Host::new(
        Caps {
            remove_data: true,
            tilt: has_tilt_sensor(&app),
            ..Caps::default()
        },
        vec!["Kept with the saves in the app's storage".into()],
    );
    let pack = dir.join(PACK_FILE);
    let mut images = None;
    let (content, initial) = match stored_pack(&pack) {
        Some((content, _, len)) => {
            host.info.insert(0, game_data_line(len));
            (content, menu)
        }
        None => {
            // First start: the import screen asks for the image (Command::PickImage) and fills the pack.
            let data = Rc::new(LatePack::default());
            let slot = ImageSlot::default();
            images = Some(slot.clone());
            let screen: Box<dyn Game> =
                Box::new(ImportScreen::new(pack.clone(), data.clone(), slot, menu));
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
        top: "",
        nav_depth: 0,
        back_down: false,
        tilt: TiltSteering::default(),
        tilt_steering: false,
        perf: PerfStats::new(),
        perf_since: None,
        gpu: String::new(),
    };
    log(LOG_INFO, &format!("backend: {:?}", platform.backends));
    let platform = mb_driver::run(event_loop, platform, host, content, initial)?;
    if platform.remove_data {
        // The run has ended, so nothing has the pack open any more.
        match std::fs::remove_file(&pack) {
            Ok(()) => log(
                LOG_INFO,
                "game data removed; the disc image is asked for at the next start",
            ),
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
    // a fresh one. (The other exit, when the activity goes first, is `MainActivity.onDestroy`.) `_exit`, not `std::process::exit`: the latter runs the libraries' static destructors on
    // this thread, and one of them frees a JNI reference with the main thread's JNIEnv, which aborts the
    // process (a native crash report instead of a quiet exit). Everything worth keeping is written by now.
    // SAFETY: ends the process at once; nothing runs after it.
    unsafe { _exit(0) }
}

unsafe extern "C" {
    /// Bionic's `_exit`: end the process without running exit handlers or static destructors.
    fn _exit(status: c_int) -> !;
}
