use ort::ep;
use ort::logging::LogLevel;
use ort::session::Session;
use ort::session::builder::SessionBuilder;

pub trait OrtBase {
    fn load_model(&mut self, model_path: String, intra_threads: usize) -> Result<(), String> {
        #[cfg(feature = "cuda")]
        let providers = [ep::CUDA::default().build()];

        #[cfg(all(feature = "xnnpack", not(feature = "cuda")))]
        let providers = [ep::XNNPACK::default().build()];

        #[cfg(all(not(feature = "cuda"), not(feature = "xnnpack")))]
        let providers = [ep::CPU::default().build()];

        match SessionBuilder::new() {
            Ok(builder) => {
                let session = builder
                    .with_execution_providers(providers)
                    .map_err(|e| format!("Failed to build session: {}", e))?
                    // inter_threads = 1 is usually best for mobile/ARM to avoid context switching overhead
                    .with_inter_threads(1)
                    .map_err(|e| format!("Failed to set inter threads: {}", e))?
                    .with_intra_threads(intra_threads)
                    .map_err(|e| format!("Failed to set intra threads: {}", e))?
                    .with_optimization_level(ort::session::builder::GraphOptimizationLevel::Level3)
                    .map_err(|e| format!("Failed to set optimization level: {}", e))?
                    .with_log_level(LogLevel::Warning)
                    .map_err(|e| format!("Failed to set log level: {}", e))?
                    .commit_from_file(model_path)
                    .map_err(|e| format!("Failed to commit from file: {}", e))?;
                    
                self.set_sess(session);
                Ok(())
            }
            Err(e) => Err(format!("Failed to create session builder: {}", e)),
        }
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
            
            #[cfg(feature = "cuda")]
            eprintln!("Configured with: CUDA execution provider");
            
            #[cfg(all(feature = "xnnpack", not(feature = "cuda")))]
            eprintln!("Configured with: XNNPACK execution provider");
            
            #[cfg(all(not(feature = "cuda"), not(feature = "xnnpack")))]
            eprintln!("Configured with: CPU execution provider");
        } else {
            eprintln!("Session is not initialized.");
        }
    }

    fn set_sess(&mut self, sess: Session);
    fn sess(&self) -> Option<&Session>;
}
