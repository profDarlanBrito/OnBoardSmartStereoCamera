# UltraWideCamera

UltraWideCamera is an Android application designed to leverage the full power of your device's camera system, specifically focusing on ultra-wide-angle lenses. 

The app's standout feature is its **Dual Capture** mode: with a single tap of the shutter button, it automatically captures two photos—one at standard 1x zoom and another at the maximum wide-angle setting supported by your device.

## Features

*   **Automatic Dual Capture:** Captures a standard (1x) and an ultra-wide (minimum zoom) photo simultaneously.
*   **Intelligent Lens Detection:** Uses both logical zoom ratios and physical focal length analysis (via `Camera2Interop`) to identify and utilize ultra-wide hardware, even on devices where it's not exposed as a standard zoom level.
*   **Manual Lens Toggling:** Easily switch between the primary and ultra-wide lenses with dedicated UI buttons.
*   **Dynamic Zoom Control:** Supports the full range of zoom available on your hardware.
*   **Organized Storage:** Photos are automatically saved to a dedicated folder: `Pictures/UltraWideCamera`.
*   **Modern UI:** Built entirely with Jetpack Compose for a fluid, responsive, and expressive user experience.

## Technical Details

*   **CameraX:** Utilizes Android's CameraX library for robust camera lifecycle management and image capture.
*   **Camera2 Interop:** Hooks into the lower-level Camera2 API to query hardware characteristics like focal lengths for precise lens identification.
*   **Kotlin Coroutines:** Manages the sequential capture process asynchronously to ensure a smooth UI.
*   **StateFlow:** Uses `MutableStateFlow` in the `ViewModel` to provide a reactive UI state to the Compose layers.

## Getting Started

1.  Clone the repository.
2.  Open the project in **Android Studio**.
3.  Build and run on a physical device (Emulators usually do not support multiple camera lenses or ultra-wide simulation).
4.  Grant Camera and Storage permissions when prompted.

## Project Structure

*   `MainActivity.kt`: The entry point and Compose UI implementation.
*   `CameraViewModel.kt`: The core logic for camera initialization, lens switching, and the dual-capture sequence.
*   `PhotoRepository.kt`: Handles the `MediaStore` interactions for saving JPEG files with custom suffixes.

## License

This project is for educational and personal use.
