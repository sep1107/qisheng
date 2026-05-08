


# Vox

**Vox** is a highly configurable Android application designed for seamless audio recording, speech-to-text transcription, and advanced AI-driven text processing. By leveraging external Automatic Speech Recognition (ASR) and Large Language Model (LLM) APIs, Vox empowers users to transcribe voice memos and immediately process the text for summarization, translation, grammatical correction, and custom contextual prompts.

## Features

* **High-Fidelity Audio Recording**: Captures audio in 16kHz, 16-bit PCM WAV format to ensure optimal compatibility with modern ASR engines.
* **Customizable ASR Integration**: Interfaces with OpenAI's Whisper API by default, but can be configured to communicate with any compatible external or self-hosted ASR endpoint.
* **AI Text Processing**: Integrated LLM actions allow users to seamlessly:
  * Summarize transcriptions.
  * Translate text into specified target languages.
  * Correct grammar and spelling.
  * Execute custom user-defined prompts against the transcribed text.
* **Intent Sharing Support**: Accepts audio files shared from other applications via the native Android sharing menu (`Intent.ACTION_SEND`), processing them instantly.
* **Markdown Rendering**: Formats and presents LLM responses using built-in HTML/Markdown rendering for high readability.
* **Complete Data Control**: Users can define their own API URLs, authentication tokens, and model identifiers directly within the application settings.

## Installation

Vox is available for download across the following platforms:

* **Google Play Store**: [Download Vox on Google Play](https://play.google.com/store/apps/details?id=it.eja.vox)
* **GitHub Releases**: Pre-compiled APK files for the latest versions can be found on the [Releases page](https://github.com/eja/vox/releases).

## Configuration and Usage

Upon launching Vox for the first time, you will need to configure your API credentials. 

1. Tap the **Settings** (gear) icon in the top right corner.
2. Under the **ASR** section:
   * **API URL**: Enter your transcription endpoint (e.g., `https://api.openai.com/v1/audio/transcriptions`).
   * **Auth Token**: Enter your API Bearer token.
   * **Model**: Specify the model name (e.g., `whisper-1`).
3. Under the **LLM** section:
   * **API URL**: Enter your LLM chat completion endpoint (e.g., `https://api.openai.com/v1/chat/completions`).
   * **Auth Token**: Enter your API Bearer token.
   * **Model**: Specify the text model name (e.g., `gpt-4o` or `gpt-3.5-turbo`).
   * **Language**: Specify your preferred default language for translations and summarizations.
4. Tap **Save**.

### Recording and Processing

* Tap the **Microphone** icon to begin recording. You will be prompted to grant audio recording permissions upon first use.
* Tap the **Stop** icon to end the recording. The app will automatically upload the audio to your configured ASR endpoint.
* Once the transcription is complete, tap the **AI** icon (located in the top left corner of the app bar) to open the LLM action menu and select your desired text processing operation.

## Permissions

* `RECORD_AUDIO`: Required to capture voice input via the device microphone.
* `INTERNET`: Required to communicate with the configured external APIs.

## Building from Source

To build the project locally:

1. Clone the repository:
   ```bash
   git clone https://github.com/eja/vox.git
   ```
2. Open the project in Android Studio.
3. Sync the Gradle files to download dependencies (Jetpack Compose, OkHttp, CommonMark, etc.).
4. Build and deploy to your emulator or physical device.
