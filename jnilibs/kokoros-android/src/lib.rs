use jni::objects::{JClass, JString};
use jni::sys::{jfloatArray, jint, jlong};
use jni::JNIEnv;
use kokoros::tts::koko::{InitConfig, TTSKoko};
use tokio::runtime::Builder;
use tokio::runtime::Runtime;

struct KokoroEngine {
    tts: TTSKoko,
    rt: Runtime,
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kokoros_Kokoro_init(
    mut env: JNIEnv,
    _class: JClass,
    model_path: JString,
    voices_path: JString,
    intra_threads: jint,
) -> jlong {
    let model_path: String = match env.get_string(&model_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let voices_path: String = match env.get_string(&voices_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

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
        TTSKoko::from_config(&model_path, &voices_path, config).await
    });

    let engine = Box::new(KokoroEngine { tts, rt });
    Box::into_raw(engine) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kokoros_Kokoro_speak_1raw(
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

    // tts_raw_audio args: txt, lan, style, speed, silence, req_id, inst_id, chunk_num
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
            eprintln!("TTS Error: {}", e);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kokoros_Kokoro_close(
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
