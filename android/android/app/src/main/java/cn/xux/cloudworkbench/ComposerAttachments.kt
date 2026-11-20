package cn.xux.cloudworkbench

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun ConversationAttachments(
    vm: WorkbenchModel, expanded: Boolean, enabled: Boolean, onClose: () -> Unit,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val key = vm.draftKey
    var captureName by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraDir = File(context.cacheDir, "camera")
    val takePicture = remember {
        object : ActivityResultContracts.TakePicture() {
            override fun createIntent(context: Context, input: Uri): Intent =
                super.createIntent(context, input).apply {
                    clipData = ClipData.newRawUri("photo", input)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
        }
    }
    val camera = rememberLauncherForActivityResult(takePicture) { saved ->
        val photo = captureName?.let { File(cameraDir, it) }
        captureName = null
        if (photo != null) {
            if (saved) {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.camera", photo)
                vm.importImages(key, listOf(uri), photo)
            } else photo.delete()
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_MESSAGE_IMAGES)) {
        vm.importImages(key, it)
    }
    val imageFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        vm.importImages(key, it)
    }
    val textFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) vm.importText(key, it)
    }

    fun launch(error: String, action: () -> Unit) {
        onClose()
        vm.reportAttachmentError(key, null)
        try {
            action()
        } catch (_: ActivityNotFoundException) {
            vm.reportAttachmentError(key, error)
        } catch (_: SecurityException) {
            vm.reportAttachmentError(key, error)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        content()
        if (expanded) Row(
            Modifier.fillMaxWidth().testTag("attachmentPanel"),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            val canAddImage = enabled && vm.imageSlots > 0
            AttachmentAction(Icons.Outlined.CameraAlt, "拍照", canAddImage) {
                launch("此设备没有可用的相机应用") {
                    try {
                        cameraDir.mkdirs()
                        val photo = File.createTempFile("capture-", ".jpg", cameraDir)
                        captureName = photo.name
                        camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.camera", photo))
                    } catch (e: Exception) {
                        captureName?.let { File(cameraDir, it).delete() }
                        captureName = null
                        if (e is ActivityNotFoundException) throw e
                        vm.reportAttachmentError(key, "无法启动相机")
                    }
                }
            }
            AttachmentAction(Icons.Outlined.PhotoLibrary, "相册", canAddImage) {
                launch("无法打开相册") { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            }
            AttachmentAction(Icons.Outlined.FolderOpen, "图片文件", canAddImage) {
                launch("无法打开文件选择器") { imageFiles.launch(arrayOf("image/*")) }
            }
            AttachmentAction(Icons.Outlined.Description, "导入文本", enabled) {
                launch("无法打开文件选择器") { textFile.launch(arrayOf("text/*", "application/json", "application/xml")) }
            }
        }
    }
}

@Composable
private fun RowScope.AttachmentAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f),
        color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)) {
            Icon(icon, null, Modifier.size(18.dp), tint = if (enabled) Accent else Muted)
            Text(label, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) Ink else Muted)
        }
    }
}

@Composable
internal fun DraftImageTray(images: List<DraftImage>, canRemove: Boolean, onRemove: (DraftImage) -> Unit) {
    var preview by remember { mutableStateOf<DraftImage?>(null) }
    if (images.isNotEmpty()) {
        LazyRow(
            Modifier.fillMaxWidth().testTag("draftImages"),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(images, key = { it.upload.localId.toString() }) { image ->
                Box(Modifier.size(64.dp)) {
                    Surface(
                        onClick = { preview = image }, modifier = Modifier.fillMaxSize().testTag("draftImagePreview"),
                        shape = RoundedCornerShape(12.dp), color = Background
                    ) {
                        Image(image.thumbnail.asImageBitmap(), "预览 ${image.name}", contentScale = ContentScale.Crop)
                    }
                    IconButton(
                        onClick = { onRemove(image) }, enabled = canRemove, modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .92f), shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Outlined.Close, "移除 ${image.name}", Modifier.padding(4.dp).size(14.dp))
                        }
                    }
                }
            }
        }
    }
    preview?.let { image ->
        val bitmap by produceState<Bitmap?>(null, image) {
            value = withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(image.upload.bytes, 0, image.upload.bytes.size) }
        }
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(image.name, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
            text = {
                Image((bitmap ?: image.thumbnail).asImageBitmap(), image.name,
                    Modifier.fillMaxWidth().heightIn(max = 440.dp), contentScale = ContentScale.Fit)
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("关闭") } }
        )
    }
}
