use ort::ep;
use ort::logging::LogLevel;
use ort::session::Session;
use ort::session::builder::SessionBuilder;

use std::path::Path;

pub trait OrtBase {
    fn load_model(&mut self, model_path: String, mut intra_threads: usize, xnnpack_threads: usize) -> Result<(), String> {
        // Default to 4 threads if 0 is provided, typical for mobile Big cores.
        if intra_threads == 0 {
            intra_threads = 4;
        }

        let optimized_path_str = format!("{}.optimized", model_path);
        let optimized_path = Path::new(&optimized_path_str);
        let model_path_obj = Path::new(&model_path);
        
        let mut is_stale = false;
        if optimized_path.exists() {
            if let (Ok(m1), Ok(m2)) = (model_path_obj.metadata(), optimized_path.metadata()) {
                if let (Ok(t1), Ok(t2)) = (m1.modified(), m2.modified()) {
                    if t1 > t2 {
                        log::info!("Original model is newer than optimized cache. Re-optimizing...");
                        is_stale = true;
                    }
                }
            }
        }

        let mut builder = SessionBuilder::new()
            .map_err(|e| format!("Failed to create session builder: {}", e))?
            .with_intra_threads(intra_threads)
            .map_err(|e| format!("Failed to set intra threads: {}", e))?
            .with_config_entry("session.intra_op.allow_spinning", "0")
            .map_err(|e| format!("Failed to disable intra_op spinning: {}", e))?
            .with_config_entry("session.inter_op.allow_spinning", "0")
            .map_err(|e| format!("Failed to disable inter_op spinning: {}", e))?
            .with_log_level(LogLevel::Warning)
            .map_err(|e| format!("Failed to set log level: {}", e))?
            .with_execution_providers([ep::CPU::default().build()])
            .map_err(|e| format!("Failed to set CPU EP: {}", e))?;

        let (final_path, opt_level) = if optimized_path.exists() && !is_stale {
            log::info!("Loading pre-optimized model from: {}", optimized_path_str);
            (optimized_path_str, ort::session::builder::GraphOptimizationLevel::Disable)
        } else {
            log::info!("Optimizing model and saving to: {}", optimized_path_str);
            builder = builder.with_optimized_model_path(&optimized_path_str)
                .map_err(|e| format!("Failed to set optimized model path: {}", e))?;
            (model_path, ort::session::builder::GraphOptimizationLevel::Level3)
        };

        let session = builder
            .with_optimization_level(opt_level)
            .map_err(|e| format!("Failed to set optimization level: {}", e))?
            .commit_from_file(final_path)
            .map_err(|e| format!("Failed to commit from file: {}", e))?;
            
        self.set_sess(session);
        Ok(())
    }

    fn print_info(&self) {
        if let Some(session) = self.sess() {
            eprintln!("Input names:");
            for input in session.inputs() {
                eprintln!("  - {}", input.name());
            }
            eprintln!("Output names:");
            for output in session.outputs() {
                eprintln!("  - {}", output.name());
            }
            
            eprintln!("Configured with: {} execution provider", self.get_execution_provider());
        } else {
            eprintln!("Session is not initialized.");
        }
    }

    fn get_execution_provider(&self) -> &'static str {
        #[cfg(feature = "cuda")]
        return "CUDA";

        #[cfg(all(feature = "xnnpack", not(feature = "cuda")))]
        return "XNNPACK";

        #[cfg(all(not(feature = "cuda"), not(feature = "xnnpack")))]
        return "CPU";
    }

    fn set_sess(&mut self, sess: Session);
    fn sess(&self) -> Option<&Session>;
}
