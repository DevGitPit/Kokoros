use jni::objects::{JClass, JString};
use jni::sys::{jfloatArray, jint, jlong, jstring};
use jni::JNIEnv;
use kokoros::tts::koko::{InitConfig, TTSKoko};
use tokio::runtime::Builder;
use tokio::runtime::Runtime;
use log::{info, error, LevelFilter};
use android_logger::Config;

struct KokoroEngine {
    tts: TTSKoko,
    rt: Runtime,
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kokoros_KokoroJNI_init(
    mut env: JNIEnv,
    _class: JClass,
    model_path: JString,
    voices_path: JString,
    espeak_data_path: JString,
    intra_threads: jint,
    xnnpack_threads: jint,
) -> jlong {
    android_logger::init_once(
        Config::default()
            .with_tag("KokoroNative")
            .with_max_level(LevelFilter::Debug)
    );

    info!("Initializing Kokoro Native Engine (Single Instance)...");

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

    // Set ESPEAK_DATA_PATH so libespeak-ng can find phoneme data
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
            xnnpack_threads: xnnpack_threads as usize,
            ..InitConfig::default()
        };
        TTSKoko::from_config(&model_path, &voices_path, config).await
    });

    let engine = Box::new(KokoroEngine { tts, rt });
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

    info!("JNI speak_raw: voice={}, speed={}", voice_str, speed);

    let audio_result = engine.rt.block_on(async {
        engine.tts.tts_raw_audio(
            &text_str,
            "en-us",
            &voice_str,
            speed,
            None,
            None,
            None,
            None
        )
    });

    match audio_result {
        Ok(samples) => {
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
pub extern "system" fn Java_com_kokoros_KokoroJNI_get_1backend_1info(
    env: JNIEnv,
    _class: JClass,
    engine_ptr: jlong,
) -> jstring {
    if engine_ptr == 0 {
        return std::ptr::null_mut();
    }

    let engine = unsafe { &mut *(engine_ptr as *mut KokoroEngine) };
    let info = engine.tts.get_backend_info();
    
    match env.new_string(info) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
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
