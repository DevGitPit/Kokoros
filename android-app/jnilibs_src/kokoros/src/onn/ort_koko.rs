use std::borrow::Cow;

use super::ort_base;
use crate::utils::debug::format_debug_prefix;
use model_schema::v1_0_timestamped::DURATIONS;
use ndarray::{ArrayBase, IxDyn, OwnedRepr};
use ort::{
    session::{Session, SessionInputValue, SessionInputs},
    value::{Tensor, Value},
};
use ort_base::OrtBase;

mod model_schema {
    pub const STYLE: &str = "style";
    pub const SPEED: &str = "speed";

    pub mod v1_0_timestamped {
        // We define primary and fallback keys as a const array
        pub const DURATIONS: &str = "durations";
    }
}

pub enum ModelStrategy {
    Standard(Session),
    Timestamped(Session),
}

pub struct OrtKoko {
    inner: Option<ModelStrategy>,
    tokens_key: String,
    audio_key: String,
}

impl OrtBase for OrtKoko {
    fn set_sess(&mut self, sess: Session) {
        let output_count = sess.outputs().len();
        
        // Detect tokens/input_ids key
        self.tokens_key = sess.inputs().iter()
            .find(|i| i.name() == "tokens" || i.name() == "input_ids")
            .map(|i| i.name().to_string())
            .unwrap_or_else(|| "tokens".to_string());

        // Detect audio/waveform key
        self.audio_key = sess.outputs().iter()
            .find(|o| o.name() == "audio" || o.name() == "waveform" || o.name() == "waveforms")
            .map(|o| o.name().to_string())
            .unwrap_or_else(|| "audio".to_string());

        let strategy = if output_count > 1 {
            tracing::info!(
                "OrtKoko: Timestamped backend activated ({} outputs). Keys: {} -> {}",
                output_count, self.tokens_key, self.audio_key
            );
            ModelStrategy::Timestamped(sess)
        } else {
            tracing::info!(
                "OrtKoko: Standard backend activated ({} output). Keys: {} -> {}",
                output_count, self.tokens_key, self.audio_key
            );
            ModelStrategy::Standard(sess)
        };

        self.inner = Some(strategy);
    }

    fn sess(&self) -> Option<&Session> {
        self.inner.as_ref().map(|strategy| match strategy {
            ModelStrategy::Standard(sess) => sess,
            ModelStrategy::Timestamped(sess) => sess,
        })
    }
}
impl OrtKoko {
    pub fn new(model_path: String, intra_threads: usize, xnnpack_threads: usize) -> Result<Self, String> {
        let mut instance = OrtKoko { 
            inner: None,
            tokens_key: "tokens".to_string(),
            audio_key: "audio".to_string(),
        };
        instance.load_model(model_path, intra_threads, xnnpack_threads)?;
        Ok(instance)
    }

    pub fn strategy(&self) -> Option<&ModelStrategy> {
        self.inner.as_ref()
    }

    fn prepare_inputs(
        tokens_key: &str,
        tokens: Vec<Vec<i64>>,
        styles: Vec<Vec<f32>>,
        speed: f32,
    ) -> Result<Vec<(Cow<'static, str>, SessionInputValue<'static>)>, Box<dyn std::error::Error>>
    {
        let shape = [tokens.len(), tokens[0].len()];
        let tokens_tensor =
            Tensor::from_array((shape, tokens.into_iter().flatten().collect::<Vec<i64>>()))?;

        let shape_style = [styles.len(), styles[0].len()];
        let style_tensor = Tensor::from_array((
            shape_style,
            styles.into_iter().flatten().collect::<Vec<f32>>(),
        ))?;

        let speed_tensor = Tensor::from_array(([1], vec![speed]))?;

        Ok(vec![
            (
                Cow::Owned(tokens_key.to_string()),
                SessionInputValue::Owned(Value::from(tokens_tensor)),
            ),
            (
                Cow::Borrowed(model_schema::STYLE),
                SessionInputValue::Owned(Value::from(style_tensor)),
            ),
            (
                Cow::Borrowed(model_schema::SPEED),
                SessionInputValue::Owned(Value::from(speed_tensor)),
            ),
        ])
    }

    pub fn infer(
        &mut self,
        tokens: Vec<Vec<i64>>,
        styles: Vec<Vec<f32>>,
        speed: f32,
        request_id: Option<&str>,
        instance_id: Option<&str>,
        chunk_number: Option<usize>,
    ) -> Result<(ArrayBase<OwnedRepr<f32>, IxDyn>, Option<Vec<f32>>), Box<dyn std::error::Error>>
    {
        let debug_prefix = format_debug_prefix(request_id, instance_id);
        let chunk_info = chunk_number
            .map(|n| format!("Chunk: {}, ", n))
            .unwrap_or_default();
        tracing::debug!(
            "{} {}inference start. Tokens: {}",
            debug_prefix,
            chunk_info,
            tokens.len()
        );

        let strategy = self.inner.as_mut().ok_or("Session is not initialized.")?;
        let audio_key = self.audio_key.clone();
        let tokens_key = self.tokens_key.clone();
        let inputs = Self::prepare_inputs(&tokens_key, tokens.clone(), styles, speed)?;
        match strategy {
            ModelStrategy::Standard(sess) => {
                let outputs = sess.run(SessionInputs::from(inputs))?;

                let (shape, data) = outputs[audio_key.as_str()]
                    .try_extract_tensor::<f32>()
                    .map_err(|_| format!("Standard Model: Could not find output tensor '{}'", audio_key))?;

                let shape_vec: Vec<usize> = shape.into_iter().map(|&i| i as usize).collect();
                tracing::debug!("Standard Model: output shape: {:?}", shape_vec);
                let audio_array = ArrayBase::from_shape_vec(shape_vec, data.to_vec())?;

                Ok((audio_array, None))
            }
            ModelStrategy::Timestamped(sess) => {
                let outputs = sess.run(SessionInputs::from(inputs))?;

                let (shape, data) = outputs[audio_key.as_str()]
                    .try_extract_tensor::<f32>()
                    .map_err(|_| format!("Timestamped Model: Could not find output tensor '{}'", audio_key))?;

                let shape_vec: Vec<usize> = shape.into_iter().map(|&i| i as usize).collect();
                tracing::debug!("Timestamped Model: output shape: {:?}", shape_vec);
                let audio_array = ArrayBase::from_shape_vec(shape_vec, data.to_vec())?;

                let durations_vec = outputs[DURATIONS]
                    .try_extract_tensor::<f32>()
                    .map(|(_, d)| d.to_vec())
                    .map_err(|_| format!(
                        "Timestamped Model Error: Expected output tensor '{}' of type f32.",
                        DURATIONS
                    ))?;

                Ok((audio_array, Some(durations_vec)))
            }
        }
    }
}
