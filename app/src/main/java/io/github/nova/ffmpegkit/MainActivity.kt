package io.github.nova.ffmpegkit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import io.github.nova.ffmpegkit.editor.EditorScreen
import io.github.nova.ffmpegkit.theme.FFmpegKitTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()

        // Use FFmpegKit library API
        FFmpegKit.printInfo()

        setContent {
            FFmpegKitTheme {
                DemoScreen()
            }
        }
    }
}

@Composable
private fun DemoScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Edit") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("FFmpeg info") })
            }
            when (tab) {
                0 -> EditorScreen()
                else -> FFmpegInfoScreen(
                    version = FFmpegKit.getVersion(),
                    buildConfig = FFmpegKit.getBuildConfiguration(),
                    libassStatus = FFmpegKit.checkLibassAvailability()
                )
            }
        }
    }
}

@Composable
private fun FFmpegInfoScreen(
    version: String,
    buildConfig: String,
    libassStatus: String
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            InfoSection(title = "FFmpeg Version", content = version)
        }

        item {
            InfoSection(title = "libass Status", content = libassStatus)
        }

        item {
            InfoSection(title = "Build Configuration", content = buildConfig)
        }
    }
}

@Composable
private fun InfoSection(title: String, content: String) {
    Text(
        text = title,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Text(
        text = content,
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
    )
}
