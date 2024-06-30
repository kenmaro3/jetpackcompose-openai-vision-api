package com.example.openaivisionapi

import okhttp3.logging.HttpLoggingInterceptor
import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.guava.await
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Define API request data classes
data class GPTRequest(val model: String, val messages: List<Message>, val max_tokens: Int)
data class Message(val role: String, val content: List<Content>)
data class Content(val type: String, val text: String? = null, val image_url: ImageUrl? = null)
data class ImageUrl(val url: String)

// Define API response data classes
data class GPTResponse(val choices: List<Choice>)
data class Choice(val message: MessageContent)
data class MessageContent(val content: String)


class AuthInterceptor(private val apiKey: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer $apiKey")
            .build()
        return chain.proceed(request)
    }
}

// Define API service interface
interface GPTService {
    @Headers("Content-Type: application/json")
    @POST("v1/chat/completions")
    suspend fun getDescription(@Body request: GPTRequest): GPTResponse
}

class MainActivity : ComponentActivity() {
    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService
    private val apiKey = ""  // Replace with your OpenAI API key

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        outputDirectory = getOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()

        setContent {
            MyApp(outputDirectory, cameraExecutor, apiKey)
        }
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists()) mediaDir else filesDir
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

@Composable
fun MyApp(outputDirectory: File, cameraExecutor: ExecutorService, apiKey: String) {
    val context = LocalContext.current
    var hasCameraPermission by remember { mutableStateOf(false) }
    var capturedImageUri by remember { mutableStateOf<Uri?>(null) }
    var description by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            val previewView = remember { PreviewView(context) }
            AndroidView(
                factory = { previewView },
                modifier = Modifier.weight(1f)
            ) {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(context as LifecycleOwner, cameraSelector, preview)
                } catch (exc: Exception) {
                    Log.e("CameraX", "Use case binding failed", exc)
                }
            }

            Button(onClick = {
                captureImage(
                    context,
                    outputDirectory,
                    cameraExecutor,
                    previewView,
                    onImageCaptured = { uri ->
                        capturedImageUri = uri
                        val bitmap = BitmapFactory.decodeStream(context.contentResolver.openInputStream(uri))
                        Log.d("MainActivity", "Getting description from image...")
                        isLoading = true
                        coroutineScope.launch {
                            getDescriptionFromImage(context, bitmap, apiKey) { response ->
                                description = response
                                isLoading = false
                            }
                        }
                    },
                    onError = { exception -> Log.e("CameraX", "Photo capture failed", exception) }
                )
            }) {
                Text("Capture Image")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        capturedImageUri?.let { uri ->
            val bitmap = BitmapFactory.decodeStream(context.contentResolver.openInputStream(uri))
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.height(200.dp))
            if (isLoading) {
                CircularProgressIndicator()
            } else {
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(description, modifier = Modifier.padding(16.dp))
    }
}


fun captureImage(
    context: Context,
    outputDirectory: File,
    cameraExecutor: ExecutorService,
    previewView: PreviewView,
    onImageCaptured: (Uri) -> Unit,
    onError: (Exception) -> Unit
) {
    val imageCapture = ImageCapture.Builder().build()

    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener({
        val cameraProvider = cameraProviderFuture.get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                context as LifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )

            Log.d("CameraX", "Use case binding successful")

            // Proceed with capturing the image after binding is successful
            val photoFile = File(outputDirectory, "${System.currentTimeMillis()}.jpg")
            Log.d("CameraX", "Photo file: $photoFile")
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
            imageCapture.takePicture(
                outputOptions,
                cameraExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exception: ImageCaptureException) {
                        onError(exception)
                        Log.e("CameraX", "Photo capture failed: ${exception.message}", exception)
                    }

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val savedUri = Uri.fromFile(photoFile)
                        Log.d("CameraX", "Photo capture succeeded: $savedUri")
                        onImageCaptured(savedUri)
                    }
                }
            )
        } catch (exc: Exception) {
            onError(exc)
        }
    }, ContextCompat.getMainExecutor(context))
}

suspend fun getDescriptionFromImage(context: Context, bitmap: Bitmap, apiKey: String, onResponse: (String) -> Unit) {
    val logging = HttpLoggingInterceptor().apply {
        setLevel(HttpLoggingInterceptor.Level.BODY)
    }

    val client = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(apiKey))
        .addInterceptor(logging)
        .connectTimeout(30, TimeUnit.SECONDS)  // Connection timeout
        .readTimeout(30, TimeUnit.SECONDS)     // Read timeout
        .writeTimeout(30, TimeUnit.SECONDS)    // Write timeout
        .build()

    val retrofit = Retrofit.Builder()
        .baseUrl("https://api.openai.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .client(client)
        .build()

    val service = retrofit.create(GPTService::class.java)

    val byteArrayOutputStream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
    val base64Image = android.util.Base64.encodeToString(byteArrayOutputStream.toByteArray(), android.util.Base64.DEFAULT)
    val imageUrl = "data:image/png;base64,$base64Image"

    val textContent = Content(type = "text", text = "Please describe what is in this image.")
    val imageContent = Content(type = "image_url", image_url = ImageUrl(url = imageUrl))
    val messages = listOf(Message(role = "user", content = listOf(textContent, imageContent)))
    val request = GPTRequest(model = "gpt-4o", messages = messages, max_tokens = 300)

    withContext(Dispatchers.IO) {
        try {
            Log.d("API Request", "Request: $request")
            val response = service.getDescription(request)
            Log.d("API Response", "Response: $response")
            val description = response.choices[0].message.content
            withContext(Dispatchers.Main) {
                onResponse(description)
            }
        } catch (e: Exception) {
            Log.e("API Error", "Error: ${e.message}", e)
            withContext(Dispatchers.Main) {
                onResponse("Error: ${e.message}")
            }
        }
    }
}

