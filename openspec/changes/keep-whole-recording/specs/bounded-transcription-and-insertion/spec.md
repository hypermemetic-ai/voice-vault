# Spec Delta

## ADDED Requirements

### Requirement: Whole recording reaches the model
When a recording contains detected speech, transcription SHALL send the whole converted recording to the model, not a trimmed span. A recording with no detected speech, including after the low-frequency rescue, SHALL still return no speech without calling the model.

#### Scenario: Silence around speech
- **WHEN** a synthetic recording has 4 s of silence, 3 s of speech-like sound and 4 s of silence
- **THEN** the stub transcriber receives audio as long as the whole 11 s recording

#### Scenario: No speech
- **WHEN** a synthetic recording is silent throughout
- **THEN** the result reports no speech and the stub transcriber is not called
