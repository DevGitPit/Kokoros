use ort::ep;
use ort::logging::LogLevel;
use ort::session::Session;
use ort::session::builder::SessionBuilder;

pub trait OrtBase {
    fn load_model(&mut self, model_path: String, intra_threads: usize) -> Result<(), String> {
        let builder = SessionBuilder::new()
            .map_err(|e| format!("Failed to create session builder: {}", e))?
            .with_optimization_level(ort::session::builder::GraphOptimizationLevel::Level3)
            .map_err(|e| format!("Failed to set optimization level: {}", e))?
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

        let session = builder
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
            
            eprintln!("Configured with: {} execution provider", self.get_execution_provider());
        } else {
            eprintln!("Session is not initialized.");
        }
    }

    fn get_execution_provider(&self) -> &'static str {
        return "CPU";
    }

    fn set_sess(&mut self, sess: Session);
    fn sess(&self) -> Option<&Session>;
}
