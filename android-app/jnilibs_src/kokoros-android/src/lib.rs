use jni::objects::{JClass, JString};
use jni::sys::{jfloatArray, jint, jlong};
use jni::JNIEnv;
use kokoros::tts::koko::{InitConfig, TTSKokoParallel};
use tokio::runtime::Builder;
use tokio::runtime::Runtime;
use std::sync::atomic::{AtomicUsize, Ordering};
use log::{info, error, LevelFilter};
use android_logger::Config;

struct KokoroEngine {
    tts: TTSKokoParallel,
    rt: Runtime,
    counter: AtomicUsize,
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kokoros_KokoroJNI_init(
    mut env: JNIEnv,
    _class: JClass,
    model_path: JString,
    voices_path: JString,
    espeak_data_path: JString,
    intra_threads: jint,
) -> jlong {
    // Initialize Android logger with Debug level
    android_logger::init_once(
        Config::default()
            .with_tag("KokoroNative")
            .with_max_level(LevelFilter::Debug)
    );

    info!("Initializing Kokoro Native Engine (Parallel, 2 instances)...");

    let model_path: String = match env.get_string(&model_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let voices_path: String = match env.get_string(&voices_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let espeak_data_path: String = match env.get_string(&espeak_data_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    // Set ESPEAK_DATA_PATH for libespeak-ng
    unsafe {
        std::env::set_var("ESPEAK_DATA_PATH", &espeak_data_path);
    }

    let rt = match Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
    {
        Ok(rt) => rt,
        Err(_) => return 0,
    };

    let tts = rt.block_on(async {
        let config = InitConfig {
            intra_threads: intra_threads as usize,
            ..InitConfig::default()
        };
        TTSKokoParallel::from_config_with_instances(&model_path, &voices_path, config, 2).await
    });

    let engine = Box::new(KokoroEngine { tts, rt, counter: AtomicUsize::new(0) });
    Box::into_raw(engine) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kokoros_KokoroJNI_speak_1raw(
    mut env: JNIEnv,
    _class: JClass,
    engine_ptr: jlong,
    text: JString,
    voice: JString,
    speed: f32,
) -> jfloatArray {
    if engine_ptr == 0 {
        return std::ptr::null_mut();
    }

    let engine = unsafe { &mut *(engine_ptr as *mut KokoroEngine) };

    let text_str: String = match env.get_string(&text) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let voice_str: String = match env.get_string(&voice) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    info!("JNI speak_raw: text='{}', speed={}, voice={}", text_str, speed, voice_str);

    // Get a model instance (round-robin)
    let worker_id = engine.counter.fetch_add(1, Ordering::SeqCst);
    let model_instance = engine.tts.get_model_instance(worker_id);

    // tts_raw_audio args: txt, lan, style, speed, silence, req_id, inst_id, chunk_num
    let audio_result = engine.rt.block_on(async {
        engine.tts.tts_raw_audio_with_instance(
            &text_str,
            "en-us",
            &voice_str,
            speed,
            None,
            None,
            None,
            None,
            model_instance
        )
    });

    match audio_result {
        Ok(samples) => {
            info!("JNI speak_raw: generated {} samples", samples.len());
            let output_array = match env.new_float_array(samples.len() as i32) {
                Ok(arr) => arr,
                Err(_) => return std::ptr::null_mut(),
            };
            
            if env.set_float_array_region(&output_array, 0, &samples).is_err() {
                 return std::ptr::null_mut();
            }
            output_array.into_raw()
        }
        Err(e) => {
            error!("TTS Error: {}", e);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kokoros_KokoroJNI_close(
    _env: JNIEnv,
    _class: JClass,
    engine_ptr: jlong,
) {
    if engine_ptr != 0 {
        unsafe {
            let _ = Box::from_raw(engine_ptr as *mut KokoroEngine);
        }
    }
}
