#[cfg(feature = "cuda")]
use ort::execution_providers::cuda::CUDAExecutionProvider;
use ort::execution_providers::cpu::CPUExecutionProvider;
use ort::session::builder::SessionBuilder;
use ort::session::Session;
use ort::logging::LogLevel;

pub trait OrtBase {
    fn load_model(&mut self, model_path: String) -> Result<(), String> {
        #[cfg(feature = "cuda")]
        let providers = [CUDAExecutionProvider::default().build()];

        #[cfg(not(feature = "cuda"))]
        let providers = [CPUExecutionProvider::default().build()];

        match SessionBuilder::new() {
            Ok(builder) => {
                let session = builder
                    .with_execution_providers(providers)
                    .map_err(|e| format!("Failed to build session: {}", e))?
                    // Force specific thread count to avoid slow efficiency cores.
                    // Set '5' for 1 Prime + 4 performance coress (e.g., SD 7+ Gen 3).
                    // Set '4' for standard 4-big-core setups.
                    .with_intra_threads(5)
                    .map_err(|e| format!("Failed to set threads: {}", e))?
                    // Optional: Ensure max optimization level
                    .with_optimization_level(ort::session::builder::GraphOptimizationLevel::Level3)
                    .map_err(|e| format!("Failed to set opt level: {}", e))?
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
            for input in &session.inputs {
                eprintln!("  - {}", input.name);
            }
            eprintln!("Output names:");
            for output in &session.outputs {
                eprintln!("  - {}", output.name);
            }

            #[cfg(feature = "cuda")]
            eprintln!("Configured with: CUDA execution provider");

            #[cfg(not(feature = "cuda"))]
            eprintln!("Configured with: CPU execution provider");
        } else {
            eprintln!("Session is not initialized.");
        }
    }

    fn set_sess(&mut self, sess: Session);
    fn sess(&self) -> Option<&Session>;
}
