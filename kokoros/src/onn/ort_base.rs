#[cfg(feature = "cuda")]
use ort::execution_providers::CUDAExecutionProvider;
use ort::execution_providers::CPUExecutionProvider;
#[cfg(feature = "xnnpack")]
use ort::execution_providers::XNNPACKExecutionProvider;
use ort::session::Session;
use ort::session::builder::GraphOptimizationLevel;

pub trait OrtBase {
    fn load_model(&mut self, model_path: String) -> Result<(), String> {
        let session = Session::builder()
            .map_err(|e| format!("Failed to create session builder: {}", e))?
            // Set execution provider
            .with_execution_providers([
                #[cfg(feature = "cuda")]
                CUDAExecutionProvider::default().build(),
                #[cfg(feature = "xnnpack")]
                XNNPACKExecutionProvider::default().build(),
                CPUExecutionProvider::default().build(),
            ])
            .map_err(|e| format!("Failed to set execution providers: {}", e))?
            // Force specific thread count to avoid slow efficiency cores.
            // Set '5' for 1 Prime + 4 performance cores (e.g., SD 7+ Gen 3).
            // Set '4' for standard 4-big-core setups.
            .with_intra_threads(5)
            .map_err(|e| format!("Failed to set threads: {}", e))?
            // Optional: Ensure max optimization level
            .with_optimization_level(GraphOptimizationLevel::Level3)
            .map_err(|e| format!("Failed to set opt level: {}", e))?
            // Load model
            .commit_from_file(model_path)
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

            #[cfg(feature = "cuda")]
            eprintln!("Configured with: CUDA execution provider");

            #[cfg(feature = "xnnpack")]
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
