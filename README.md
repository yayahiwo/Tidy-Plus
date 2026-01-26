<H1>Thanks to https://github.com/slavabarkov for hthe orignal Tidy</H1>

<p>I needed the app to do a little bit more, with the help of AI i was able to add the folowing :</p>
<ul>
<li> Added image-to-image similarity threshold control (persisted) and applied it to “Find Similar” results filtering.</li>
<li> Added multi-select in the grid (long-press to enter selection mode + checkboxes/overlay).</li>
<li> Added bulk Delete for selected photos using Android’s system permission flow (MediaStore delete requests / recoverable permission).</li>
<li> Added bulk Move for selected photos using folder picker + write permission (updates RELATIVE_PATH; primary storage support).</li>
<li> Added automatic index cleanup when photos are deleted (removes them from the Room CLIP index + in- memory lists).</li>
<li> Added a persistent “folders to index” setting (by MediaStore buckets) and updated indexing to only index selected folders.</li>
<li> Changed startup so the app doesn’t auto-index immediately; it waits for folder selection and a Start action (with auto-prompt on first run).</li>
<li> Added indexing progress + indexed count display on the indexing screen.</li>
<li> Added re-index progress UI directly in the search screen after folder changes, and auto-refreshes the grid when indexing finishes.</li>
<li> Added pinch-to-zoom for the thumbnails grid by changing column count (now supports much smaller thumbnails; up to 24 columns).</li>
<li> Made the grid remember its zoom level (persists the current column count across navigation).</li>
<li> Fixed grid visuals: square thumbnails (no stretching) and uniform 2px spacing horizontally/vertically.</li>
<li> In image-to-image results, added dimensions under each thumbnail (e.g., 800x600) and only show them for those results.</li>
<li> Added a Back button to return from image-to-image results to the full “all images” grid.</li>
<li> Improved the single-image screen: shows file location, file size, and pixel dimensions under the image.</li>
<li> Updated single-image UI controls: BACK button, Find Similar button, and added icons (search icon on Find Similar; close icon on BACK).</li>
<li> Fixed dark mode readability in single-image screen (text uses theme colors) and made the image background black in dark theme.</li>
<li> Enabled/configured pinch-to-zoom on the single image view (PhotoView scales).</li>
<li> Adjusted single-image navigation so swipe-to-next/previous is handled via PhotoView’s fling callback (and doesn’t break pinch zoom).</li>
<li> Converted the top-row search screen buttons to icon-only buttons (Search, Back, Clear, Folders) with appropriate icons.</li>
<li> Updated Gradle config so the project builds with AGP’s Java 17+ requirement (points Gradle to Android Studio’s bundled JDK).</li>
</ul>
<h2>Screenshots of the UI</h2>

Original Author Discription start:
===================================
# TIDY - Text-to-Image Discovery
<div style="display:flex;">
<a href='https://github.com/slavabarkov/tidy/releases/latest/download/tidy-release.apk'><img src='res/banner-apk.jpg' alt='Download for Android' height='50'/></a>
<a href='https://f-droid.org/packages/com.slavabarkov.tidy/'><img src='res/banner-fdroid.png' alt='Get it on F-Droid' height='50'/></a>
</div>
</br>

Offline semantic Text-to-Image and Image-to-Image search on your Android phone! Powered by quantized state-of-the-art large-scale vision-language pretrained CLIP model and ONNX Runtime inference engine.
</br>
<div style="display:flex;">
<img alt="Text-to-Image Search" src="res/text-to-image.jpg" width="50%"><img alt="Image-to-Image Search" src="res/image-to-image.jpg" width="50%">
</div>

## Approach
TIDY uses OpenAI CLIP (Contrastive Language-Image Pre-Training) model - a neural network trained on a variety of vision-language pairs. CLIP efficiently learns visual concepts from natural language supervision, which allows TIDY to use it for Text-to-Image retrieval. CLIP can also be used to get high-quality image representations with high universality and generalization ability providing great results in the Image-to-Image retrieval task.

The model used in TIDY is based on open source CLIP implementation [OpenCLIP](https://github.com/mlfoundations/open_clip) pretrained on [LAION-2B](https://huggingface.co/datasets/laion/laion2B-en), a ~2B sample subset of [LAION-5B](https://laion.ai/blog/laion-5b/) dataset with english captions.
| ![CLIP](https://raw.githubusercontent.com/mlfoundations/open_clip/main/docs/CLIP.png) |
|:--:|
| Image Credit: https://github.com/openai/CLIP |


## Features and Usage
### First Launch
During the first launch TIDY will need to scan through your photo library and create an index of your images. This indexing process may take some time, but it's a one-time event. Once this initial indexing process is complete, the app will store the index on your device, and any new photos you add to your photo library will be automatically added to the index on the subsequent app launches.

### Privacy and Security
TIDY works entirely offline, ensuring your privacy and security are never compromised. None of your data or images are ever uploaded to a remote server or shared with third parties, ensuring your personal information stays safe and secure.  It also means that you can use it anytime, anywhere, even in areas with poor or no internet connectivity.

### Text-to-Image Search

<details>
<summary>Video demonstration</summary>
<video src="https://user-images.githubusercontent.com/46378663/226463103-f146f4a6-79fa-4d6a-8371-af45db431ba5.mp4"></video>
</details>

Simply type in a description of the image you are looking for, and TIDY will retrieve the most relevant matches from your local image library. Text-to-Image search functionality in TIDY goes beyond traditional keyword-based searches! You can use longer and more detailed descriptions to find the exact image you have in mind.

### Image-to-Image Search

<details>
<summary>Video demonstration</summary>
<video src="https://user-images.githubusercontent.com/46378663/226463174-93071c91-dfa1-4ece-9b15-194fd8fc3c5b.mp4"></video>
</details>

Search for visually similar images by choosing a photo from your device's gallery. TIDY will analyze the image and retrieve images with similar visual features, allowing you to explore and discover new images in a whole new way.

## Citation
```bibtex
@Misc{tidy,
  title =        {TIDY (Text-to-Image Discovery): Offline Semantic Text-to-Image and Image-to-Image Search on Android Powered by the Vision-Language Pretrained CLIP Model.},
  author =       {Viacheslav Barkov},
  howpublished = {\url{https://github.com/slavabarkov/tidy}},
  year =         {2023}
}
```
