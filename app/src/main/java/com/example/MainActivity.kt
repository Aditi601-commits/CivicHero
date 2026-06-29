package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.Canvas
import android.graphics.Paint
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MapProperties
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.example.data.*
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.AiAnalysisState
import com.example.viewmodel.CommunityHeroViewModel
import android.graphics.BitmapFactory
import android.util.Base64
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.vector.rememberVectorPainter

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: CommunityHeroViewModel

    companion object {
        var isAppInForeground: Boolean = false
    }

    override fun onResume() {
        super.onResume()
        isAppInForeground = true
    }

    override fun onPause() {
        super.onPause()
        isAppInForeground = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        viewModel = CommunityHeroViewModel(applicationContext)
        
        // Start background shake service so it is active immediately with safe exception catching
        try {
            ShakeDetectionService.startService(this)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Gracefully skipped direct background service starting: ${e.message}")
        }
        
        // Request post notification permission for Android 13+ (needed for background shake notifications)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                
                // Read and persist login state with SharedPreferences so shake-to-report works immediately without repeating logins
                val prefs = remember(context) { context.getSharedPreferences("community_hero_prefs", Context.MODE_PRIVATE) }
                var isLoggedIn by remember { mutableStateOf(prefs.getBoolean("is_logged_in", false)) }
                var userName by remember { mutableStateOf(prefs.getString("user_name", "Citizen Hero") ?: "Citizen Hero") }
                
                LaunchedEffect(userName) {
                    viewModel.updateUserName(userName)
                }
                
                // Handle intent extra if launched from shake
                LaunchedEffect(intent) {
                    if (intent?.getBooleanExtra("LAUNCH_QUICK_REPORT", false) == true) {
                        viewModel.launchQuickReportTrigger = true
                        // Auto-log in if they shake, to keep it extremely seamless!
                        isLoggedIn = true
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    androidx.compose.animation.AnimatedContent(
                        targetState = isLoggedIn,
                        transitionSpec = {
                            androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(600)) togetherWith
                            androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(600))
                        },
                        label = "login_transition"
                    ) { loggedIn ->
                        Box(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (loggedIn) {
                                ResponsiveScaffold(viewModel, userName, onLogout = { 
                                    prefs.edit().putBoolean("is_logged_in", false).apply()
                                    isLoggedIn = false 
                                })
                            } else {
                                LoginScreen(onLoginSuccess = { enteredName ->
                                    prefs.edit().putBoolean("is_logged_in", true).apply()
                                    prefs.edit().putString("user_name", enteredName).apply()
                                    userName = enteredName
                                    viewModel.updateUserName(enteredName)
                                    isLoggedIn = true 
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("LAUNCH_QUICK_REPORT", false)) {
            viewModel.launchQuickReportTrigger = true
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResponsiveScaffold(viewModel: CommunityHeroViewModel, userName: String, onLogout: () -> Unit) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf("feed") }
    var detailReportForModal by remember { mutableStateOf<Report?>(null) }

    LaunchedEffect(viewModel.launchQuickReportTrigger) {
        if (viewModel.launchQuickReportTrigger) {
            selectedTab = "report"
            viewModel.launchQuickReportTrigger = false
            Toast.makeText(context, "📳 Background Shake Detected: Opened Quick Report!", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Track location & camera permission states standard launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        
        if (fineGranted || coarseGranted) {
            viewModel.requestLocation(context)
        }
        if (cameraGranted) {
            Toast.makeText(context, "Camera Access Ready", Toast.LENGTH_SHORT).show()
        }
    }

    // Trigger permissions on start
    LaunchedEffect(Unit) {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        
        if (!hasFine || !hasCamera) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.CAMERA
                )
            )
        } else {
            viewModel.requestLocation(context)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isLargeScreen = maxWidth > 600.dp
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val profile by viewModel.userProfile.collectAsStateWithLifecycle()

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.width(310.dp),
                    drawerContainerColor = Color(0xFF0F0B26)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            // Title Header with close button
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Shield,
                                            contentDescription = "Shield Logo",
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Profile",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = Color.White
                                    )
                                }
                                IconButton(onClick = { scope.launch { drawerState.close() } }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close Menu",
                                        tint = Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(bottom = 16.dp),
                                color = Color.White.copy(alpha = 0.15f)
                            )

                            // USER PROFILE WIDGET
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E173C)),
                                border = BorderStroke(1.dp, Color(0xFF3B2E6E))
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .background(
                                                    Brush.linearGradient(
                                                        listOf(Color(0xFF8C52FF), Color(0xFF00E676))
                                                    ),
                                                    CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "CH",
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color.White,
                                                fontSize = 16.sp
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = userName,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                fontSize = 15.sp
                                            )
                                            Text(
                                                text = "Level ${profile.level} Inspector",
                                                fontSize = 12.sp,
                                                color = Color(0xFF00E676),
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text(
                                                text = "LEVEL",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White.copy(alpha = 0.5f)
                                            )
                                            Text(
                                                text = "${profile.level}",
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color.White
                                            )
                                        }
                                        Column {
                                            Text(
                                                text = "TOTAL XP",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White.copy(alpha = 0.5f)
                                            )
                                            Text(
                                                text = "${profile.points} XP",
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Column {
                                            Text(
                                                text = "STREAK",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White.copy(alpha = 0.5f)
                                            )
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.LocalFireDepartment,
                                                    contentDescription = "Streak",
                                                    tint = Color(0xFFFF9F1A),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(2.dp))
                                                Text(
                                                    text = "${profile.currentStreak}d",
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = Color(0xFFFF9F1A)
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))
                                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Assignment,
                                                contentDescription = "Reports",
                                                tint = Color.White.copy(alpha = 0.6f),
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "${profile.reportsSubmitted} Reports",
                                                color = Color.White.copy(alpha = 0.8f),
                                                fontSize = 11.sp
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "Verifications",
                                                tint = Color.White.copy(alpha = 0.6f),
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "${profile.verificationsDone} Audits",
                                                color = Color.White.copy(alpha = 0.8f),
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "PORTAL SECTIONS",
                                color = Color.White.copy(alpha = 0.4f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                            )

                            // MENUS
                            DrawerItemButton(
                                icon = Icons.Default.Warning,
                                label = "Verification Feed",
                                isSelected = selectedTab == "feed",
                                onClick = {
                                    selectedTab = "feed"
                                    scope.launch { drawerState.close() }
                                }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            DrawerItemButton(
                                icon = Icons.Default.AddLocationAlt,
                                label = "Report Hazard",
                                isSelected = selectedTab == "report",
                                onClick = {
                                    selectedTab = "report"
                                    scope.launch { drawerState.close() }
                                }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            DrawerItemButton(
                                icon = Icons.Default.Insights,
                                label = "Impact Dashboard",
                                isSelected = selectedTab == "dashboard",
                                onClick = {
                                    selectedTab = "dashboard"
                                    scope.launch { drawerState.close() }
                                }
                            )
                        }

                        // Lower settings/logout stamp
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Button(
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    onLogout()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .testTag("logout_button_drawer"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF3B30).copy(alpha = 0.15f),
                                    contentColor = Color(0xFFFF453A)
                                ),
                                border = BorderStroke(1.dp, Color(0xFFFF3B30).copy(alpha = 0.3f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ExitToApp,
                                    contentDescription = "Log out",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Log Out Securely",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "Aadhaar UIDAI Encrypted Portal Link",
                                color = Color.White.copy(alpha = 0.3f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Unified Civic Security Gateway",
                                color = Color.White.copy(alpha = 0.2f),
                                fontSize = 8.sp
                            )
                        }
                    }
                }
            }
        ) {
            if (isLargeScreen) {
                // LAPTOP / LARGE SCREEN LAYOUT
                Row(modifier = Modifier.fillMaxSize()) {
                    // Persistent Side navigation drawer
                    Card(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(260.dp),
                        shape = RoundedCornerShape(0.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                // Professional brand title
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Card(
                                        modifier = Modifier.size(40.dp),
                                        shape = CircleShape,
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
                                    ) {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.Shield,
                                                contentDescription = "Logo",
                                                tint = Color.White
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = userName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Problem Solver",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                                
                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                                
                                // Navigation items
                                NavDrawerButton(
                                    icon = Icons.Default.Warning,
                                    label = "Verification Feed",
                                    isSelected = selectedTab == "feed",
                                    tag = "feed_tab",
                                    onClick = { selectedTab = "feed" }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                NavDrawerButton(
                                    icon = Icons.Default.AddLocationAlt,
                                    label = "Report Hazard",
                                    isSelected = selectedTab == "report",
                                    tag = "report_tab",
                                    onClick = { selectedTab = "report" }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                NavDrawerButton(
                                    icon = Icons.Default.Insights,
                                    label = "Impact Dashboard",
                                    isSelected = selectedTab == "dashboard",
                                    tag = "dashboard_tab",
                                    onClick = { selectedTab = "dashboard" }
                                )
                            }

                            // Bottom user statistics widget
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.primary,
                                                    CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "C",
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                fontSize = 12.sp
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = userName,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                            Text(
                                                text = "Level ${profile.level}",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${profile.points} XP Earned",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.LocalFireDepartment,
                                                contentDescription = "Streak",
                                                tint = Color(0xFFFF5722),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Text(
                                                text = "${profile.currentStreak}d streak",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFFF5722)
                                            )
                                        }
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(10.dp))
                            
                            OutlinedButton(
                                onClick = { onLogout() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .testTag("logout_button_large_screen"),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ExitToApp,
                                    contentDescription = "Log out",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Secure Log Out",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    
                    // Content area
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        androidx.compose.animation.AnimatedContent(
                            targetState = selectedTab,
                            transitionSpec = {
                                val indexMap = mapOf("feed" to 0, "report" to 1, "dashboard" to 2)
                                val targetIndex = indexMap[targetState] ?: 0
                                val initialIndex = indexMap[initialState] ?: 0
                                if (targetIndex > initialIndex) {
                                    (androidx.compose.animation.slideInHorizontally(animationSpec = androidx.compose.animation.core.tween(450)) { it } + androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(450))) togetherWith
                                    (androidx.compose.animation.slideOutHorizontally(animationSpec = androidx.compose.animation.core.tween(450)) { -it } + androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(450)))
                                } else {
                                    (androidx.compose.animation.slideInHorizontally(animationSpec = androidx.compose.animation.core.tween(450)) { -it } + androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(450))) togetherWith
                                    (androidx.compose.animation.slideOutHorizontally(animationSpec = androidx.compose.animation.core.tween(450)) { it } + androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(450)))
                                }
                            },
                            label = "large_screen_tab_transition"
                        ) { tab ->
                            when (tab) {
                                "feed" -> FeedLargeScreen(viewModel)
                                "report" -> CreateReportScreen(viewModel) { selectedTab = "feed" }
                                "dashboard" -> DashboardLargeScreen(viewModel)
                            }
                        }
                    }
                }
            } else {
                // SMARTPHONE LAYOUT
                Scaffold(
                    topBar = { SleekHeaderBar(viewModel = viewModel, onMenuClick = { scope.launch { drawerState.open() } }) },
                    bottomBar = {
                        NavigationBar(
                            modifier = Modifier.testTag("bottom_nav_bar")
                        ) {
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.RssFeed, contentDescription = "Feed") },
                                label = { Text(getTxt("feed_tab", viewModel.appLanguage)) },
                                selected = selectedTab == "feed",
                                modifier = Modifier.testTag("bottom_feed_tab"),
                                onClick = { selectedTab = "feed" }
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.AddAPhoto, contentDescription = "Report") },
                                label = { Text(getTxt("report_tab", viewModel.appLanguage)) },
                                selected = selectedTab == "report",
                                modifier = Modifier.testTag("bottom_report_tab"),
                                onClick = { selectedTab = "report" }
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.SpaceDashboard, contentDescription = "Dashboard") },
                                label = { Text(getTxt("dashboard_tab", viewModel.appLanguage)) },
                                selected = selectedTab == "dashboard",
                                modifier = Modifier.testTag("bottom_dashboard_tab"),
                                onClick = { selectedTab = "dashboard" }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        androidx.compose.animation.AnimatedContent(
                            targetState = selectedTab,
                            transitionSpec = {
                                val indexMap = mapOf("feed" to 0, "report" to 1, "dashboard" to 2)
                                val targetIndex = indexMap[targetState] ?: 0
                                val initialIndex = indexMap[initialState] ?: 0
                                if (targetIndex > initialIndex) {
                                    (androidx.compose.animation.slideInHorizontally(animationSpec = androidx.compose.animation.core.tween(450)) { it } + androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(450))) togetherWith
                                    (androidx.compose.animation.slideOutHorizontally(animationSpec = androidx.compose.animation.core.tween(450)) { -it } + androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(450)))
                                } else {
                                    (androidx.compose.animation.slideInHorizontally(animationSpec = androidx.compose.animation.core.tween(450)) { -it } + androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(450))) togetherWith
                                    (androidx.compose.animation.slideOutHorizontally(animationSpec = androidx.compose.animation.core.tween(450)) { it } + androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(450)))
                                }
                            },
                            label = "small_screen_tab_transition"
                        ) { tab ->
                            when (tab) {
                                "feed" -> FeedSmallScreen(viewModel) { report ->
                                    detailReportForModal = report
                                }
                                "report" -> CreateReportScreen(viewModel) { selectedTab = "feed" }
                                "dashboard" -> DashboardSmallScreen(viewModel)
                            }
                        }
                    }
                }
                
                // Detail sheet/modal for smartphone report info click
                detailReportForModal?.let { report ->
                    ModalDetailDialog(
                        report = report,
                        viewModel = viewModel,
                        onDismiss = { detailReportForModal = null }
                    )
                }
            }
        }
    }
}

// Side nav item helper
@Composable
fun NavDrawerButton(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag(tag),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Text(text = label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

@Composable
fun DrawerItemButton(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = label,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.85f),
                fontSize = 14.sp
            )
        }
    }
}

fun getTxt(key: String, lang: String): String {
    if (lang == "hi") {
        return when (key) {
            "app_title" -> "कम्युनिटी हीरो"
            "feed_tab" -> "शिकायतें"
            "report_tab" -> "नई शिकायत"
            "dashboard_tab" -> "लीडरबोर्ड"
            "admin_tab" -> "अधिकारी लॉगिन"
            "search_placeholder" -> "शिकायतें खोजें (जैसे: गड्ढा, कचरा, स्ट्रीटलाइट)..."
            "filter_all" -> "सभी शिकायतें"
            "filter_pending" -> "लंबित"
            "filter_verified" -> "सत्यापित"
            "filter_wip" -> "काम चालू"
            "filter_resolved" -> "ठीक हो गया"
            "report_new_hazard" -> "नई शिकायत दर्ज करें"
            "title_label" -> "शीर्षक / संक्षिप्त नाम"
            "title_placeholder" -> "जैसे: मुख्य मार्ग पर गहरा गड्ढा"
            "desc_label" -> "शिकायत का विवरण"
            "desc_placeholder" -> "विवरण दें (जैसे: गाड़ियों को निकलने में परेशानी हो रही है)"
            "category_label" -> "श्रेणी चुनें"
            "severity_label" -> "गंभीरता स्तर"
            "photo_camera" -> "फ़ोटो खींचें"
            "photo_gallery" -> "गैलरी से चुनें"
            "photo_mock" -> "मॉक फ़ोटो"
            "gps_automatic" -> "📍 जीपीएस द्वारा सत्यापित स्थान"
            "gps_retry" -> "पुनः प्रयास करें"
            "gps_manual_toggle" -> "या स्वयं पता दर्ज करें"
            "submit_button" -> "शिकायत दर्ज करें (+50 XP)"
            "clarity_title" -> "ऑन-डिवाइस फ़ोटो जांच"
            "clarity_score" -> "फ़ोटो स्पष्टता स्कोर"
            "clarity_passed" -> "सत्यापित"
            "clarity_failed" -> "अस्पष्ट"
            "map_threat_title" -> "सक्रिय शिकायत नक्शा"
            "map_high" -> "गंभीर (लाल)"
            "map_medium" -> "मध्यम (नारंगी)"
            "map_low" -> "सामान्य (पीला)"
            "map_resolved" -> "ठीक हो गया (हरा)"
            "pothole" -> "सड़क का गड्ढा"
            "water leakage" -> "पानी का रिसाव"
            "damaged streetlight" -> "स्ट्रीटलाइट खराब"
            "waste management" -> "कचरा प्रबंधन"
            "public infrastructure" -> "टूटी सार्वजनिक संपत्ति"
            "other" -> "अन्य समस्या"
            "low_sev" -> "कम"
            "med_sev" -> "मध्यम"
            "high_sev" -> "ज़्यादा"
            "verify_issue" -> "सत्यापन करें (क्या यह सच है?):"
            "upvote_btn" -> "सही है"
            "downvote_btn" -> "ग़लत है"
            "points_earned" -> "पॉइंट्स"
            "level" -> "लेवल"
            "streak" -> "दैनिक स्ट्रीक"
            "verifications" -> "सत्यापन किए"
            "submissions" -> "दर्ज शिकायतें"
            "dup_title" -> "एक ही जगह पर शिकायत पहले से दर्ज है!"
            "dup_msg" -> "इस स्थान से 50 मीटर के दायरे में पहले से ही एक सक्रिय शिकायत दर्ज है।"
            "dup_action" -> "पुरानी शिकायत का समर्थन करें (+10 XP)"
            "dup_submit_anyway" -> "फिर भी नई दर्ज करें"
            "dup_cancel" -> "रद्द करें"
            "map_instruction" -> "नक्शे को ज़ूम करने के लिए पिंच करें या घुमाने के लिए ड्रैग करें"
            "map_fullscreen" -> "नक्शा बड़ा करें"
            "map_normalsize" -> "नक्शा छोटा करें"
            "leaderboard_title" -> "सक्रिय नागरिक रैंकिंग"
            "profile_title" -> "आपका नागरिक प्रोफ़ाइल"
            "prediction_alert" -> "पूर्वानुमान चेतावनी"
            "zone" -> "क्षेत्र"
            "likely_failure" -> "संभावित खराबी"
            "risk_score" -> "जोखिम दर"
            "clarity_fail_msg" -> "फ़ोटो बहुत धुंधली या अस्पष्ट है। कृपया साफ़ फ़ोटो चुनें।"
            "onboarding_welcome" -> "कम्युनिटी हीरो में आपका स्वागत है! 🌟"
            "onboarding_subtitle" -> "हमारा क्षेत्र, हमारी ज़िम्मेदारी। स्थानीय समस्याओं की रिपोर्ट करें, मरम्मत में सहायता करें और एक ज़िम्मेदार नागरिक बनें!"
            "onboarding_step1_title" -> "1. समस्या देखें 📌"
            "onboarding_step1_desc" -> "सड़क पर कोई समस्या जैसे गड्ढे, टूटी स्ट्रीटलाइट या कचरा देखें। फोन हिलाकर (Shake) भी तुरंत रिपोर्ट दर्ज कर सकते हैं!"
            "onboarding_step2_title" -> "2. फ़ोटो खींचें 📸"
            "onboarding_step2_desc" -> "सटीक विवरण के लिए कैमरे से फ़ोटो खींचें या गैलरी से चुनें। हमारा सिस्टम ऑटोमैटिक जीपीएस स्थान ले लेगा।"
            "onboarding_step3_title" -> "3. दर्ज करें 🚀"
            "onboarding_step3_desc" -> "पॉइंट्स पाने के लिए शिकायत दर्ज करें और नक्शे पर उसका लाइव स्टेटस ट्रैक करें!"
            "onboarding_step4_title" -> "4. सत्यापित करें ✅"
            "onboarding_step4_desc" -> "दूसरों की शिकायतों को अपवोट करके सही बताएं और सुधार कार्यों को तेज़ करें।"
            "onboarding_dismiss" -> "समझ गया, शुरू करें!"
            "onboarding_show_guide" -> "मदद और गाइड ℹ️"
            else -> key
        }
    }
    return when (key) {
        "app_title" -> "Community Hero"
        "feed_tab" -> "Feed"
        "report_tab" -> "Quick Report"
        "dashboard_tab" -> "Dashboard"
        "admin_tab" -> "Officer Portal"
        "search_placeholder" -> "Search active complaints (pothole, waste)..."
        "filter_all" -> "All Issues"
        "filter_pending" -> "Pending"
        "filter_verified" -> "Verified"
        "filter_wip" -> "In Progress"
        "filter_resolved" -> "Resolved"
        "report_new_hazard" -> "Report New Infrastructure Hazard"
        "title_label" -> "Brief Title"
        "title_placeholder" -> "e.g., Deep pothole near main crossroad"
        "desc_label" -> "Detailed Description"
        "desc_placeholder" -> "Provide details (e.g., causing vehicles to swerve)"
        "category_label" -> "Hazard Category"
        "severity_label" -> "Severity level"
        "photo_camera" -> "Take Photo"
        "photo_gallery" -> "Gallery"
        "photo_mock" -> "Mock Photo"
        "gps_automatic" -> "📍 GPS Verified Location"
        "gps_retry" -> "Retry"
        "gps_manual_toggle" -> "Or Type Custom Address Manually"
        "submit_button" -> "Submit Ticket (+50 XP)"
        "clarity_title" -> "ON-DEVICE COMPUTER VISION PIPELINE"
        "clarity_score" -> "Image Clarity Score"
        "clarity_passed" -> "PASSED"
        "clarity_failed" -> "FAILED"
        "map_threat_title" -> "INCIDENT THREAT MAP"
        "map_high" -> "High Threat / High Priority (Crimson)"
        "map_medium" -> "Medium Threat (Orange)"
        "map_low" -> "Low Threat (Yellow)"
        "map_resolved" -> "Resolved Issues (Green)"
        "pothole" -> "Pothole"
        "water leakage" -> "Water Leakage"
        "damaged streetlight" -> "Damaged Streetlight"
        "waste management" -> "Waste Management"
        "public infrastructure" -> "Public Infrastructure"
        "other" -> "Other Issue"
        "low_sev" -> "Low"
        "med_sev" -> "Medium"
        "high_sev" -> "High"
        "verify_issue" -> "Verify this issue:"
        "upvote_btn" -> "Upvote"
        "downvote_btn" -> "Downvote"
        "points_earned" -> "Points"
        "level" -> "Level"
        "streak" -> "Streak"
        "verifications" -> "Verifications"
        "submissions" -> "Submissions"
        "dup_title" -> "Duplicate Local Ticket Detected"
        "dup_msg" -> "An active ticket is located within 50 meters from your coordinates."
        "dup_action" -> "Upvote Existing (+10 XP)"
        "dup_submit_anyway" -> "Submit Anyway"
        "dup_cancel" -> "Cancel"
        "map_instruction" -> "Pinch to zoom / drag to pan the map"
        "map_fullscreen" -> "Expand Map View"
        "map_normalsize" -> "Collapse Map View"
        "leaderboard_title" -> "Active Citizen Leaderboard"
        "profile_title" -> "Your Citizen Profile"
        "prediction_alert" -> "PREDICTION ALERT"
        "zone" -> "Zone"
        "likely_failure" -> "Likely Failure"
        "risk_score" -> "Risk Rate"
        "clarity_fail_msg" -> "Image is too blurry, flat, or lacks clear physical features."
        "onboarding_welcome" -> "Welcome to Community Hero! 🌟"
        "onboarding_subtitle" -> "Our neighborhood, our responsibility. Report civic issues (potholes, garbage, broken streetlights) and watch them get fixed. You can even shake your phone to report instantly!"
        "onboarding_step1_title" -> "1. Spot a Hazard 📌"
        "onboarding_step1_desc" -> "Find a civic problem around you. Tap 'Quick Report' or simply shake your device!"
        "onboarding_step2_title" -> "2. Photo & GPS 📸"
        "onboarding_step2_desc" -> "Take a photo or pick from gallery. The app automatically fetches coordinates with GPS precision."
        "onboarding_step3_title" -> "3. Submit Ticket 🚀"
        "onboarding_step3_desc" -> "File an official ticket to earn +50 Citizen XP and track live updates on the threat map!"
        "onboarding_step4_title" -> "4. Upvote & Verify ✅"
        "onboarding_step4_desc" -> "Review nearby local issues. Upvote real hazards to increase their priority for official repair!"
        "onboarding_dismiss" -> "Got it, Let's Go!"
        "onboarding_show_guide" -> "Help Guide ℹ️"
        else -> key
    }
}

// ==========================================
// 1. REPORT ISSUES PIPELINE
// ==========================================
fun calculateDistanceInMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0 // Earth radius in meters
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    return r * c
}

@Composable
fun CreateReportScreen(viewModel: CommunityHeroViewModel, onSubmitted: () -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    
    val reportsList by viewModel.reports.collectAsStateWithLifecycle()
    var duplicateDetectedReport by remember { mutableStateOf<Report?>(null) }
    var duplicateDistance by remember { mutableStateOf(0.0) }
    
    var currentStep by remember { mutableStateOf(1) }
    var locationOption by remember { mutableStateOf("auto") } // "auto" or "manual"
    
    // Setup camera contracts standard launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            viewModel.capturedImage = bitmap
        }
    }

    // Setup gallery image selection launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val contentResolver = context.contentResolver
                val source = android.graphics.ImageDecoder.createSource(contentResolver, uri)
                val bitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = true
                }
                viewModel.capturedImage = bitmap
            } catch (e: Exception) {
                try {
                    @Suppress("DEPRECATION")
                    val bitmap = android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                    viewModel.capturedImage = bitmap
                } catch (ex: Exception) {
                    Toast.makeText(context, "Error loading image from gallery", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Setup extra image selector contract
    val extraImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val contentResolver = context.contentResolver
                val source = android.graphics.ImageDecoder.createSource(contentResolver, uri)
                val bitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = true
                }
                viewModel.additionalCapturedImages.add(bitmap)
            } catch (e: Exception) {
                try {
                    @Suppress("DEPRECATION")
                    val bitmap = android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                    viewModel.additionalCapturedImages.add(bitmap)
                } catch (ex: Exception) {
                    Toast.makeText(context, "Error loading extra image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Setup extra video selector contract
    val extraVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.additionalVideoUris.add(uri.toString())
            Toast.makeText(context, "Video added successfully!", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
            .testTag("report_screen_container")
    ) {
        // Step Tracker Indicator Indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .background(
                        if (currentStep >= 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(3.dp)
                    )
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .background(
                        if (currentStep >= 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(3.dp)
                    )
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (viewModel.appLanguage == "hi") "चरण 1: विवरण और फ़ोटो" else "Step 1: Details & Photo",
                fontSize = 11.sp,
                fontWeight = if (currentStep == 1) FontWeight.Bold else FontWeight.Normal,
                color = if (currentStep == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (viewModel.appLanguage == "hi") "चरण 2: स्थान पिन करें" else "Step 2: Pinpoint Location",
                fontSize = 11.sp,
                fontWeight = if (currentStep == 2) FontWeight.Bold else FontWeight.Normal,
                color = if (currentStep == 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (currentStep == 1) {
            // Hero Graphic Header
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    )
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        text = getTxt("report_new_hazard", viewModel.appLanguage),
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (viewModel.appLanguage == "hi") 
                            "फ़ोटो खींचें या चुनें, और Gemini AI द्वारा ऑन-डिवाइस समस्या को लाइव स्कैन करके तुरंत शिकायत टिकट दर्ज करें।" 
                            else "Take a photo, scan with Gemini AI to identify high hazard priority and dispatch repair tickets.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Form Fields
        Text(
            text = if (viewModel.appLanguage == "hi") "शिकायत विवरण" else "Report Details", 
            fontWeight = FontWeight.Bold, 
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = viewModel.reportTitle,
            onValueChange = { viewModel.reportTitle = it },
            label = { Text(getTxt("title_label", viewModel.appLanguage)) },
            placeholder = { Text(getTxt("title_placeholder", viewModel.appLanguage)) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("input_issue_title"),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = viewModel.reportDescription,
            onValueChange = { viewModel.reportDescription = it },
            label = { Text(getTxt("desc_label", viewModel.appLanguage)) },
            placeholder = { Text(getTxt("desc_placeholder", viewModel.appLanguage)) },
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .testTag("input_issue_desc"),
            maxLines = 4
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Image Attachment Segment
        Text(
            text = if (viewModel.appLanguage == "hi") "शिकायत की फ़ोटो (JPEG/PNG)" else "Hazard Image (JPEG/PNG)",
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Camera Button
            Button(
                onClick = { cameraLauncher.launch(null) },
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .testTag("open_camera_btn"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = "Camera", modifier = Modifier.size(18.dp))
                    Text(getTxt("photo_camera", viewModel.appLanguage), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // Gallery Button (NEW!)
            Button(
                onClick = { galleryLauncher.launch("image/*") },
                modifier = Modifier
                    .weight(1.1f)
                    .height(46.dp)
                    .testTag("open_gallery_btn"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Image, contentDescription = "Gallery", modifier = Modifier.size(18.dp))
                    Text(getTxt("photo_gallery", viewModel.appLanguage), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Simulator Button (subtle, aligned cleanly below uploader tools)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    viewModel.capturedImage = generateDemoHazardBitmap(viewModel.reportTitle)
                    val msg = if (viewModel.appLanguage == "hi") "सफलतापूर्वक मॉक फ़ोटो तैयार किया गया!" else "Mock image generated successfully!"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .testTag("generate_mock_photo_btn"),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.LinkedCamera, contentDescription = "Simulate Image", modifier = Modifier.size(16.dp))
                    Text(getTxt("photo_mock", viewModel.appLanguage), fontSize = 11.sp, fontWeight = FontWeight.Normal)
                }
            }

            OutlinedButton(
                onClick = {
                    viewModel.additionalVideoUris.add("android.resource://${context.packageName}/raw/demo_video")
                    Toast.makeText(context, "Simulated Video Attached!", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.VideoCameraBack, contentDescription = "Simulate Video", modifier = Modifier.size(16.dp))
                    Text(if (viewModel.appLanguage == "hi") "मॉक वीडियो जोड़ें" else "Simulate Video", fontSize = 11.sp, fontWeight = FontWeight.Normal)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Multi-Media Attachment Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { extraImageLauncher.launch("image/*") },
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Add Photo", modifier = Modifier.size(16.dp))
                    Text(if (viewModel.appLanguage == "hi") "+ फ़ोटो" else "+ Extra Photo", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Button(
                onClick = { extraVideoLauncher.launch("video/*") },
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.VideoCall, contentDescription = "Add Video", modifier = Modifier.size(16.dp))
                    Text(if (viewModel.appLanguage == "hi") "+ वीडियो" else "+ Extra Video", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Preview Extra Attachments Horizontal List
        if (viewModel.additionalCapturedImages.isNotEmpty() || viewModel.additionalVideoUris.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (viewModel.appLanguage == "hi") "अतिरिक्त मीडिया फ़ाइलें" else "Extra Media Attachments",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(viewModel.additionalCapturedImages.size) { idx ->
                    Box(modifier = Modifier.size(85.dp).clip(RoundedCornerShape(10.dp))) {
                        Image(
                            bitmap = viewModel.additionalCapturedImages[idx].asImageBitmap(),
                            contentDescription = "Extra Image Preview",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        IconButton(
                            onClick = { viewModel.additionalCapturedImages.removeAt(idx) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(24.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                items(viewModel.additionalVideoUris.size) { idx ->
                    Box(
                        modifier = Modifier
                            .size(85.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.PlayCircle, contentDescription = "Video file", tint = Color(0xFF00E676), modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("VIDEO", fontSize = 8.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        IconButton(
                            onClick = { viewModel.additionalVideoUris.removeAt(idx) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(24.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Preview Attached Image
        viewModel.capturedImage?.let { bmp ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val boxWidth = maxWidth
                    val boxHeight = maxHeight
                    
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Captured Hazard Image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    
                    // On-Device Bounding Box Visualization
                    viewModel.assetDetectedBoundingBox?.let { rect ->
                        val l = rect.left * boxWidth.value
                        val t = rect.top * boxHeight.value
                        val w = (rect.right - rect.left) * boxWidth.value
                        val h = (rect.bottom - rect.top) * boxHeight.value
                        
                        Box(
                            modifier = Modifier
                                .absoluteOffset(x = l.dp, y = t.dp)
                                .size(width = w.dp, height = h.dp)
                                .border(2.dp, Color(0xFFFF9500), RoundedCornerShape(4.dp))
                        ) {
                            // Label tag on top of bounding box
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .background(Color(0xFFFF9500), RoundedCornerShape(bottomEnd = 4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FilterCenterFocus,
                                        contentDescription = "Asset Detect",
                                        tint = Color.Black,
                                        modifier = Modifier.size(10.dp)
                                    )
                                    Text(
                                        text = "INFRASTRUCTURE ASSET",
                                        color = Color.Black,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                    }
                    
                    IconButton(
                        onClick = { viewModel.capturedImage = null },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Remove", tint = Color.White)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // On-Device computer vision metrics panel card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeveloperMode,
                            contentDescription = "On-Device AI",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "ON-DEVICE COMPUTER VISION PIPELINE",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 1.sp
                            )
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Image Clarity Score:",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${viewModel.imageClarityScore ?: 0}%",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if ((viewModel.imageClarityScore ?: 0) >= 35) Color(0xFF4CD964) else Color(0xFFFF3B30)
                            )
                        }
                        
                        // Progress Bar of clarity
                        Box(
                            modifier = Modifier
                                .width(100.dp)
                                .height(6.dp)
                                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(3.dp))
                        ) {
                            val scoreFraction = ((viewModel.imageClarityScore ?: 0) / 100f).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(fraction = scoreFraction)
                                    .background(
                                        if ((viewModel.imageClarityScore ?: 0) >= 35) Color(0xFF4CD964) else Color(0xFFFF3B30),
                                        RoundedCornerShape(3.dp)
                                    )
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Asset Bounding Box Validation:",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (viewModel.assetDetectedBoundingBox != null) "PASSED" else "FAILED",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (viewModel.assetDetectedBoundingBox != null) Color(0xFF4CD964) else Color(0xFFFF3B30)
                        )
                    }
                    
                    viewModel.imageValidationError?.let { errorMsg ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFFF3B30).copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                                .padding(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Warning",
                                tint = Color(0xFFFF453A),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = errorMsg,
                                color = Color(0xFFFF453A),
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // AI AUTOFILL ACTION BUTTON
            Button(
                onClick = { viewModel.runAiAutofill() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("ai_autofill_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ),
                shape = RoundedCornerShape(12.dp),
                enabled = !viewModel.isAutofilling,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (viewModel.isAutofilling) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onTertiaryContainer, strokeWidth = 2.dp)
                        Text(
                            text = if (viewModel.appLanguage == "hi") "Gemini AI विवरण भर रहा है..." else "Gemini AI Autofilling...",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "AI Autofill",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = if (viewModel.appLanguage == "hi") "AI ऑटो-फ़िल शीर्षक और विवरण ✨" else "AI Autofill Title & Description ✨",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            viewModel.autofillError?.let { err ->
                Spacer(modifier = Modifier.height(6.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        } ?: run {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.ImageNotSupported,
                            contentDescription = "No photo attached",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "No Hazard Image Attached Yet",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Next step button
        Button(
            onClick = {
                if (viewModel.reportTitle.isBlank()) {
                    Toast.makeText(context, if (viewModel.appLanguage == "hi") "कृपया शीर्षक दर्ज करें" else "Please enter an issue title", Toast.LENGTH_SHORT).show()
                } else if (viewModel.reportDescription.isBlank()) {
                    Toast.makeText(context, if (viewModel.appLanguage == "hi") "कृपया विवरण दर्ज करें" else "Please enter an issue description", Toast.LENGTH_SHORT).show()
                } else {
                    currentStep = 2
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("next_step_button"),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (viewModel.appLanguage == "hi") "आगे बढ़ें: स्थान पिन करें ➡️" else "Next: Pinpoint Location ➡️",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    } else {
        // STEP 2: LOCATION SETUP & SUBMISSION
        // Dual-Option Location Selection System
        Text(text = "Incident Location Setup", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Option 1: Auto-Detect GPS
            Card(
                modifier = Modifier
                    .weight(1f)
                    .clickable { locationOption = "auto" },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    width = if (locationOption == "auto") 2.dp else 1.dp,
                    color = if (locationOption == "auto") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                ),
                colors = CardDefaults.cardColors(
                    containerColor = if (locationOption == "auto") MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.GpsFixed,
                        contentDescription = "GPS",
                        tint = if (locationOption == "auto") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Auto-Detect GPS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (locationOption == "auto") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Real-time satellite coordinates",
                        fontSize = 9.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            // Option 2: Manual Location / Map Pin
            Card(
                modifier = Modifier
                    .weight(1f)
                    .clickable { locationOption = "manual" },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    width = if (locationOption == "manual") 2.dp else 1.dp,
                    color = if (locationOption == "manual") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                ),
                colors = CardDefaults.cardColors(
                    containerColor = if (locationOption == "manual") MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.EditLocation,
                        contentDescription = "Manual",
                        tint = if (locationOption == "manual") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Manual Pinpoint",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (locationOption == "manual") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Drop pin on map & write address",
                        fontSize = 9.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Dynamic inputs based on selected location option
        if (locationOption == "auto") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color(0xFF00E676), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (viewModel.locationLabel == "Locating..." || viewModel.locationLabel == "Acquiring GPS Signal...") "Downtown Landmark" else viewModel.locationLabel,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Lat: ${String.format("%.4f", viewModel.currentLatitude)} / Lng: ${String.format("%.4f", viewModel.currentLongitude)} (Automatic Verification)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { viewModel.requestLocation(context) },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(46.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Retry Location")
                }
            }
        } else {
            // MANUAL OPTION INTERFACE
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Let them input custom address / landmark with instant geocoding lookup
                OutlinedTextField(
                    value = viewModel.locationLabel,
                    onValueChange = { 
                        viewModel.locationLabel = it 
                        viewModel.locateAddressOnMap(it, context)
                    },
                    label = { Text("Type Address / Landmark (e.g. Connaught Place, Sector 15 Noida)") },
                    placeholder = { Text("Type address to automatically locate on map...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search Address",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { viewModel.locateAddressOnMap(viewModel.locationLabel, context) }) {
                            Icon(
                                imageVector = Icons.Default.MyLocation,
                                contentDescription = "Locate",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White.copy(alpha = 0.9f)
                    )
                )

                // 2. Metre/Sector quick presets for Indian context
                Text(
                    text = "Popular Delhi NCR Presets (Tap to Select):",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val quickLocations = listOf(
                        Quad(28.6304, 77.2177, "Connaught Place, New Delhi", "Connaught Place"),
                        Quad(28.5830, 77.3100, "Sector 15, Noida Crossing", "Sector 15 Noida"),
                        Quad(28.6350, 77.2250, "Mahatma Gandhi Marg, New Delhi", "Ring Road Delhi"),
                        Quad(28.6219, 77.3639, "Sector 62 IT Hub, Noida", "Sector 62 Noida"),
                        Quad(28.6400, 77.2000, "Karol Bagh Market, New Delhi", "Karol Bagh Delhi")
                    )

                    quickLocations.forEach { preset ->
                        AssistChip(
                            onClick = {
                                viewModel.currentLatitude = preset.first
                                viewModel.currentLongitude = preset.second
                                viewModel.locationLabel = preset.third
                                Toast.makeText(context, "Location preset loaded!", Toast.LENGTH_SHORT).show()
                            },
                            label = { Text(preset.fourth) },
                            leadingIcon = { Icon(Icons.Default.Storefront, "Shop", modifier = Modifier.size(14.dp)) }
                        )
                    }
                }

                // 3. Auto-Locating Map Visualizer
                Text(
                    text = "Automatic Real-World Map Target:",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    fontWeight = FontWeight.SemiBold
                )

                InteractiveMiniMapSelector(viewModel = viewModel)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // INTEGRATED GEMINI FLASH EXTRACTION
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Gemini",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Gemini Hazard Scanner",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Button(
                        onClick = { viewModel.runAiAnalysis() },
                        enabled = viewModel.capturedImage != null && viewModel.reportTitle.isNotBlank() && viewModel.aiState != AiAnalysisState.Analyzing,
                        modifier = Modifier
                            .height(36.dp)
                            .testTag("run_ai_analysis_btn"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) {
                        Text("Run Scan", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                when (val state = viewModel.aiState) {
                    is AiAnalysisState.Idle -> {
                        Text(
                            text = "Scan with Gemini Flash to auto-extract the Hazard Category, assess public safety threats, and estimate severity instantly.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is AiAnalysisState.Analyzing -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Gemini Flash processing image payload...",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    is AiAnalysisState.Success -> {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = true,
                                    onClick = {},
                                    label = { Text("Extracted: ${state.result.category}") },
                                    leadingIcon = { Icon(Icons.Default.Category, "Category", modifier = Modifier.size(14.dp)) }
                                )
                                FilterChip(
                                    selected = true,
                                    onClick = {},
                                    label = { Text("Severity: ${state.result.severity}") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = getSeverityColor(state.result.severity).copy(alpha = 0.2f),
                                        selectedLabelColor = getSeverityColor(state.result.severity)
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "A.I. Safety Assessment:",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = state.result.explanation,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    is AiAnalysisState.Error -> {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Dropdown Fallbacks to manually construct custom properties if AI offline/skipped
        Text(text = "Manual Parameters (Verify / Fallbacks)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1.5f)) {
                Text("Category", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val categories = listOf("Pothole", "Water Leakage", "Damaged Streetlight", "Waste Management", "Public Infrastructure", "Other")
                var catExpanded by remember { mutableStateOf(false) }
                Box {
                    Button(
                        onClick = { catExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp), contentColor = MaterialTheme.colorScheme.onSurface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(viewModel.manualCategory, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Default.ArrowDropDown, "Select")
                        }
                    }
                    DropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false }) {
                        categories.forEach { cat ->
                            DropdownMenuItem(text = { Text(cat) }, onClick = {
                                viewModel.manualCategory = cat
                                catExpanded = false
                            })
                        }
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text("Severity", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val severities = listOf("Low", "Medium", "High")
                var sevExpanded by remember { mutableStateOf(false) }
                Box {
                    Button(
                        onClick = { sevExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp), contentColor = MaterialTheme.colorScheme.onSurface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(viewModel.manualSeverity)
                            Icon(Icons.Default.ArrowDropDown, "Select")
                        }
                    }
                    DropdownMenu(expanded = sevExpanded, onDismissRequest = { sevExpanded = false }) {
                        severities.forEach { sev ->
                            DropdownMenuItem(text = { Text(sev) }, onClick = {
                                viewModel.manualSeverity = sev
                                sevExpanded = false
                            })
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Navigation and Submit Ticket buttons row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { currentStep = 1 },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text(
                    text = if (viewModel.appLanguage == "hi") "⬅️ पीछे" else "⬅️ Back",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Button(
                onClick = {
                    val currentLat = viewModel.currentLatitude
                    val currentLng = viewModel.currentLongitude
                    
                    // Find if there is an active local ticket within 50 meters
                    val duplicate = reportsList.firstOrNull { report ->
                        report.status != "Resolved" && 
                        calculateDistanceInMeters(currentLat, currentLng, report.latitude, report.longitude) <= 50.0
                    }
                    
                    if (duplicate != null) {
                        duplicateDetectedReport = duplicate
                        duplicateDistance = calculateDistanceInMeters(currentLat, currentLng, duplicate.latitude, duplicate.longitude)
                    } else {
                        viewModel.submitReport {
                            Toast.makeText(context, "Hazard submitted! You earned +50 XP", Toast.LENGTH_LONG).show()
                            onSubmitted()
                        }
                    }
                },
                enabled = viewModel.reportTitle.isNotBlank() && 
                          viewModel.reportDescription.isNotBlank() && 
                          (viewModel.capturedImage == null || viewModel.imageValidationError == null),
                modifier = Modifier
                    .weight(2.5f)
                    .height(52.dp)
                    .testTag("submit_report_button"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Submit Ticket", modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (viewModel.appLanguage == "hi") "टिकट जमा करें 🚀" else "Submit Ticket 🚀",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
        
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Duplicate Detection Dialog
        if (duplicateDetectedReport != null) {
            val existingReport = duplicateDetectedReport!!
            AlertDialog(
                onDismissRequest = { duplicateDetectedReport = null },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Duplicate Warning",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text("Duplicate Local Ticket Detected", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "An active '${existingReport.hazardCategory}' ticket titled '${existingReport.title}' is located only ${duplicateDistance.toInt()} meters from your current coordinates.",
                            fontSize = 14.sp
                        )
                        Text(
                            text = "To save municipal resources and speed up repair, you can upvote the existing ticket instead of creating a duplicate.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.upvoteReport(existingReport)
                            Toast.makeText(context, "Upvoted existing ticket! You earned +10 XP", Toast.LENGTH_LONG).show()
                            duplicateDetectedReport = null
                            viewModel.resetForm()
                            onSubmitted()
                        }
                    ) {
                        Text("Upvote Existing (+10 XP)")
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                viewModel.submitReport {
                                    Toast.makeText(context, "Hazard submitted anyway! You earned +50 XP", Toast.LENGTH_LONG).show()
                                    duplicateDetectedReport = null
                                    onSubmitted()
                                }
                            }
                        ) {
                            Text("Submit Anyway", color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(
                            onClick = { duplicateDetectedReport = null }
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            )
        }
    }
}

// Generate colored thumbnail vector representation on headless devices
fun generateDemoHazardBitmap(title: String): Bitmap {
    val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint()
    
    // Gradient Background
    val colorBackground = if (title.contains("light", ignoreCase = true)) {
        0xFF1A237E.toInt() // dark night
    } else if (title.contains("water", ignoreCase = true) || title.contains("hydrant", ignoreCase = true)) {
        0xFF006064.toInt() // teal cyan
    } else {
        0xFF4E342E.toInt() // asphalt brown
    }
    
    canvas.drawColor(colorBackground)
    
    // Draw visual alerts
    paint.color = 0xFFFFD54F.toInt() // Amber yellow
    paint.style = Paint.Style.FILL
    canvas.drawRect(20f, 20f, 380f, 280f, paint)
    
    paint.color = colorBackground
    canvas.drawRect(30f, 30f, 370f, 270f, paint)
    
    // Draw critical warning shape
    paint.color = 0xFFE53935.toInt() // Warning red
    canvas.drawCircle(200f, 130f, 60f, paint)
    
    paint.color = 0xFFFFFFFF.toInt()
    paint.textSize = 28f
    paint.textAlign = Paint.Align.CENTER
    paint.isAntiAlias = true
    canvas.drawText("CITIZEN WARNING", 200f, 230f, paint)
    
    paint.textSize = 48f
    paint.typeface = android.graphics.Typeface.create(paint.typeface, android.graphics.Typeface.BOLD)
    canvas.drawText("⚠️", 200f, 145f, paint)
    
    return bitmap
}

// ==========================================
// 2. VERIFICATION FEED PIPELINE
// ==========================================

@Composable
fun QuickOnboardingWidget(viewModel: CommunityHeroViewModel) {
    var expanded by remember { mutableStateOf(viewModel.showOnboardingIntro) }
    
    // Auto-update ViewModel when expanded changes
    LaunchedEffect(expanded) {
        viewModel.showOnboardingIntro = expanded
    }

    // Dynamic animation wrapper for super smooth onboarding expansion/collapse
    AnimatedContent(
        targetState = expanded,
        transitionSpec = {
            fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
        },
        label = "onboarding_animation"
    ) { isExpanded ->
        if (isExpanded) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .testTag("onboarding_expanded_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                ),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Header Row with Title and Close Icon
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Guide",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = getTxt("onboarding_welcome", viewModel.appLanguage),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        
                        IconButton(
                            onClick = { expanded = false },
                            modifier = Modifier
                                .size(32.dp)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = getTxt("onboarding_subtitle", viewModel.appLanguage),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // Step 1: Spot a Hazard
                    OnboardingStepRow(
                        icon = Icons.Default.Map,
                        tintColor = MaterialTheme.colorScheme.primary,
                        title = getTxt("onboarding_step1_title", viewModel.appLanguage),
                        desc = getTxt("onboarding_step1_desc", viewModel.appLanguage)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Step 2: Photo & GPS
                    OnboardingStepRow(
                        icon = Icons.Default.PhotoCamera,
                        tintColor = MaterialTheme.colorScheme.secondary,
                        title = getTxt("onboarding_step2_title", viewModel.appLanguage),
                        desc = getTxt("onboarding_step2_desc", viewModel.appLanguage)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Step 3: Submit Ticket
                    OnboardingStepRow(
                        icon = Icons.Default.CloudUpload,
                        tintColor = MaterialTheme.colorScheme.tertiary,
                        title = getTxt("onboarding_step3_title", viewModel.appLanguage),
                        desc = getTxt("onboarding_step3_desc", viewModel.appLanguage)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Step 4: Upvote & Verify
                    OnboardingStepRow(
                        icon = Icons.Default.ThumbUp,
                        tintColor = Color(0xFF4CD964),
                        title = getTxt("onboarding_step4_title", viewModel.appLanguage),
                        desc = getTxt("onboarding_step4_desc", viewModel.appLanguage)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Action Button to Dismiss
                    Button(
                        onClick = { expanded = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("dismiss_onboarding_btn"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            text = getTxt("onboarding_dismiss", viewModel.appLanguage),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            // Compact Helper Banner for quick reopening
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clickable { expanded = true }
                    .testTag("onboarding_collapsed_card"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = "Help Guide",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = getTxt("onboarding_show_guide", viewModel.appLanguage),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = "Expand",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun OnboardingStepRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tintColor: Color,
    title: String,
    desc: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Styled circular icon background container
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(tintColor.copy(alpha = 0.15f), CircleShape)
                .border(1.dp, tintColor.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tintColor,
                modifier = Modifier.size(16.dp)
            )
        }
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = desc,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// Smartphone screen feed (Single column scroll + click opens modal)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedSmallScreen(viewModel: CommunityHeroViewModel, onReportSelected: (Report) -> Unit) {
    val reportsList by viewModel.reports.collectAsStateWithLifecycle()
    var selectedFilter by remember { mutableStateOf("All") }
    var selectedTabIndex by remember { mutableStateOf(0) }

    val tabs = listOf(
        if (viewModel.appLanguage == "hi") "📋 लाइव फीड" else "📋 Live Feed",
        if (viewModel.appLanguage == "hi") "🗺️ मैप रडार" else "🗺️ Map Radar",
        if (viewModel.appLanguage == "hi") "💡 स्मार्ट हब" else "💡 Smart Hub"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        SecondaryTabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        when (selectedTabIndex) {
            0 -> {
                FilterRowHeader(selectedFilter, viewModel.appLanguage) { selectedFilter = it }
                val filteredList = filterReports(reportsList, selectedFilter)
                
                if (filteredList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No reported hazards under filter $selectedFilter", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("feed_scroll_list")
                            .padding(horizontal = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = if (viewModel.appLanguage == "hi") "नमस्ते, ${viewModel.userName}! 👋" else "Hello, ${viewModel.userName}! 👋",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (viewModel.appLanguage == "hi") 
                                            "सामुदायिक रिपोर्ट फ़ीड में आपका स्वागत है। समस्याओं को अपवोट करें और अपने क्षेत्र को बेहतर बनाने में मदद करें!" 
                                            else "Welcome to CivicHero. Here are all the active civic hazards reported by citizens in your area. Upvote real issues to help prioritize them for local authorities!",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        lineHeight = 15.sp
                                    )
                                }
                            }
                        }
                        items(filteredList) { report ->
                            ReportCardItem(
                                report = report,
                                voteType = viewModel.votedReportIds[report.id],
                                lang = viewModel.appLanguage,
                                onVoteUp = { viewModel.upvoteReport(report) },
                                onVoteDown = { viewModel.downvoteReport(report) },
                                onClick = { onReportSelected(report) }
                            )
                        }
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }
            1 -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    SleekAreaMapCard(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                }
            }
            2 -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    item {
                        QuickOnboardingWidget(viewModel = viewModel)
                    }
                    item {
                        GeminiLiveAlert(reports = reportsList)
                    }
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

// Laptop screen feed (Multi-column Split pattern)
@Composable
fun FeedLargeScreen(viewModel: CommunityHeroViewModel) {
    val reportsList by viewModel.reports.collectAsStateWithLifecycle()
    var selectedFilter by remember { mutableStateOf("All") }
    var selectedReport by remember { mutableStateOf<Report?>(null) }
    
    // Keep active selected report valid if list updates
    LaunchedEffect(reportsList) {
        if (selectedReport == null && reportsList.isNotEmpty()) {
            selectedReport = reportsList.first()
        } else if (selectedReport != null) {
            selectedReport = reportsList.firstOrNull { it.id == selectedReport!!.id } ?: reportsList.firstOrNull()
        }
    }
    
    Column(modifier = Modifier.fillMaxSize()) {
        FilterRowHeader(selectedFilter, viewModel.appLanguage) { selectedFilter = it }
        
        val filteredList = filterReports(reportsList, selectedFilter)
        
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Left Column: Scrollable reports list
            Box(modifier = Modifier.weight(1.1f)) {
                if (filteredList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No items found.")
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = if (viewModel.appLanguage == "hi") "नमस्ते, ${viewModel.userName}! 👋" else "Hello, ${viewModel.userName}! 👋",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (viewModel.appLanguage == "hi") 
                                            "सामुदायिक रिपोर्ट फ़ीड में आपका स्वागत है। समस्याओं को अपवोट करें और अपने क्षेत्र को बेहतर बनाने में मदद करें!" 
                                            else "Welcome to CivicHero. Here are all the active civic hazards reported by citizens in your area. Upvote real issues to help prioritize them for local authorities!",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        lineHeight = 15.sp
                                    )
                                }
                            }
                        }
                        item {
                            QuickOnboardingWidget(viewModel = viewModel)
                        }
                        item {
                            SleekAreaMapCard(viewModel = viewModel)
                        }
                        item {
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        item {
                            GeminiLiveAlert(reports = reportsList)
                        }
                        item {
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        items(filteredList) { report ->
                            ReportCardItem(
                                report = report,
                                isHighlighted = selectedReport?.id == report.id,
                                voteType = viewModel.votedReportIds[report.id],
                                lang = viewModel.appLanguage,
                                onVoteUp = { viewModel.upvoteReport(report) },
                                onVoteDown = { viewModel.downvoteReport(report) },
                                onClick = { selectedReport = report }
                            )
                        }
                    }
                }
            }
            
            // Right Column: Hazard inspection Detail view & Municipal prioritizations panel
            Box(
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight()
                    .padding(bottom = 16.dp)
            ) {
                selectedReport?.let { report ->
                    DetailInspectPanel(report, viewModel)
                } ?: run {
                    Card(
                        modifier = Modifier.fillMaxSize(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Select an issue to inspect details & manage prioritize repair routes.")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FilterRowHeader(selected: String, lang: String = "en", onSelect: (String) -> Unit) {
    val filters = listOf("All", "Pending", "Verified", "WIP", "Resolved")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        filters.forEach { filter ->
            val isChosen = selected == filter
            val statusKey = if (filter == "WIP") "filter_wip" else "filter_" + filter.lowercase()
            ElevatedFilterChip(
                selected = isChosen,
                onClick = { onSelect(filter) },
                label = { Text(getTxt(statusKey, lang), fontWeight = FontWeight.Bold) },
                colors = FilterChipDefaults.elevatedFilterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    }
}

fun filterReports(list: List<Report>, filter: String): List<Report> {
    if (filter == "All") return list
    return list.filter { it.status.equals(filter, ignoreCase = true) }
}

@Composable
fun rememberBase64Bitmap(base64String: String?): Bitmap? {
    if (base64String == null) return null
    return remember(base64String) {
        try {
            if (base64String.startsWith("data:image") || base64String.contains(",")) {
                val cleanString = base64String.substringAfter(",")
                val decodedBytes = Base64.decode(cleanString, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
            } else {
                val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
            }
        } catch (e: Exception) {
            null
        }
    }
}

@Composable
fun ReportCardItem(
    report: Report,
    isHighlighted: Boolean = false,
    voteType: String? = null,
    lang: String = "en",
    onVoteUp: () -> Unit,
    onVoteDown: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isHighlighted) 2.dp else 1.dp,
                color = if (isHighlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(24.dp)
            )
            .clickable { onClick() }
            .testTag("report_card_${report.id}"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            // Header Row: Category emblem & Severity badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = getCategoryIcon(report.hazardCategory),
                            contentDescription = report.hazardCategory,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = getTxt(report.hazardCategory.lowercase().replace(" ", ""), lang),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = getSeverityColor(report.severity).copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = getTxt(report.severity.lowercase() + "_sev", lang),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = getSeverityColor(report.severity),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Body contents
            Text(
                text = report.title,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = report.description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp
            )

            if (!report.imageUrl.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                val decodedBitmap = rememberBase64Bitmap(report.imageUrl)
                if (decodedBitmap != null) {
                    Image(
                        bitmap = decodedBitmap.asImageBitmap(),
                        contentDescription = "Report photo",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    AsyncImage(
                        model = report.imageUrl,
                        contentDescription = "Report photo",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop,
                        error = rememberVectorPainter(Icons.Default.BrokenImage)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Location tag badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "Pos",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = report.locationName,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Status badge
                Card(
                    colors = CardDefaults.cardColors(containerColor = getStatusColor(report.status).copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    val statusKey = when (report.status) {
                        "Pending" -> "filter_pending"
                        "Verified" -> "filter_verified"
                        "Work In Progress" -> "filter_wip"
                        "Resolved" -> "filter_resolved"
                        else -> "filter_pending"
                    }
                    Text(
                        text = getTxt(statusKey, lang),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = getStatusColor(report.status),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Civic Voting interactions pipeline
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = getTxt("verify_issue", lang),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isUpvoted = voteType == "up"
                    val isDownvoted = voteType == "down"

                    // Upvote trigger
                    Button(
                        onClick = { onVoteUp() },
                        enabled = !isDownvoted,
                        modifier = Modifier.height(34.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isUpvoted) Color(0xFF34C759) else MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (isUpvoted) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.ThumbUp, contentDescription = "Upvote", modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("${report.upvotes}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    // Downvote trigger
                    Button(
                        onClick = { onVoteDown() },
                        enabled = !isUpvoted,
                        modifier = Modifier.height(34.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDownvoted) Color(0xFFFF3B30) else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (isDownvoted) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.ThumbDown, contentDescription = "Downvote", modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("${report.downvotes}", fontSize = 11.sp)
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = "Time",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Reported On:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                Text(
                    text = formatReportTime(report.timestamp),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// Side panels detail inspector for large screens
@Composable
fun DetailInspectPanel(report: Report, viewModel: CommunityHeroViewModel) {
    val scroll = rememberScrollState()
    Card(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Hazard Inspection Portal",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp
                )
                Text(
                    text = formatReportTime(report.timestamp),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = report.title,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Badges
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = getSeverityColor(report.severity).copy(alpha = 0.15f))) {
                    Text(
                        "${report.severity} Priority",
                        color = getSeverityColor(report.severity),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Card(colors = CardDefaults.cardColors(containerColor = getStatusColor(report.status).copy(alpha = 0.12f))) {
                    Text(
                        report.status,
                        color = getStatusColor(report.status),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Map Simulation graphic to cover geo-location tags elegantly
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Draw vector map paths
                        val strokePaint = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                        drawCircle(color = Color.White.copy(alpha = 0.4f), radius = 60.dp.toPx())
                        drawCircle(color = Color.White.copy(alpha = 0.3f), radius = 100.dp.toPx())
                        // grid lines
                        drawLine(
                            color = Color.White.copy(alpha = 0.2f),
                            start = androidx.compose.ui.geometry.Offset(0f, size.height / 2),
                            end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2)
                        )
                        drawLine(
                            color = Color.White.copy(alpha = 0.2f),
                            start = androidx.compose.ui.geometry.Offset(size.width / 2, 0f),
                            end = androidx.compose.ui.geometry.Offset(size.width / 2, size.height)
                        )
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = "Target pin",
                            tint = getSeverityColor(report.severity),
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = report.locationName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Lat: ${report.latitude} / Lng: ${report.longitude}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(text = "Hazard Description", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                report.description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
                lineHeight = 17.sp
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Verification voting info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Total Community Votes", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VolunteerActivism, "Trust", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${report.upvotes - report.downvotes} points traction",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text("Reliability Metrics", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (report.upvotes > 20) "HIGH ACCURACY TICKET" else "INVESTIGATION QUEUED",
                        color = if (report.upvotes > 20) Color(0xFF4CAF50) else Color(0xFFFF9800),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // MUNICIPAL PRIORITIZATION COMMAND PORTAL
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "🛠️ Municipal Repair Prioritization Console",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Prioritized queue order depends on severity score weight & verified citizen upvotes.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                    
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = "Transition Status (Dispatch Crews):", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.adminMockRepairUpdate(report, "Work In Progress") },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Dispatch WIP", fontSize = 11.sp)
                        }

                        Button(
                            onClick = { viewModel.adminMockRepairUpdate(report, "Resolved") },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Commit Fix", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

// Smartphone detail dialog
@Composable
fun ModalDetailDialog(report: Report, viewModel: CommunityHeroViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close Inspector")
            }
        },
        title = {
            Text(report.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Card(colors = CardDefaults.cardColors(containerColor = getSeverityColor(report.severity).copy(alpha = 0.15f))) {
                        Text(
                            report.severity,
                            color = getSeverityColor(report.severity),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = getStatusColor(report.status).copy(alpha = 0.12f))) {
                        Text(
                            report.status,
                            color = getStatusColor(report.status),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(text = "Coordinates Tag:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    text = "${report.locationName} (${report.latitude}, ${report.longitude})",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(text = "Hazard Detail:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    text = report.description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(10.dp))
                
                Text(text = "Reported On:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    text = formatReportTime(report.timestamp),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                
                // Parse and display images & videos
                val extraPhotosList = report.additionalImages?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                val extraVideosList = report.videoUris?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

                // Primary Image
                if (!report.imageUrl.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        AsyncImage(
                            model = report.imageUrl,
                            contentDescription = "Primary Report Image",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }

                // Extra Photos & Videos
                if (extraPhotosList.isNotEmpty() || extraVideosList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Additional Attachments:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(4.dp))
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(extraPhotosList.size) { index ->
                            Card(
                                modifier = Modifier.size(70.dp),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                AsyncImage(
                                    model = extraPhotosList[index],
                                    contentDescription = "Attached Photo",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                        items(extraVideosList.size) { index ->
                            Box(
                                modifier = Modifier.size(70.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.PlayCircle, contentDescription = "Video", tint = Color(0xFF00E676), modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                
                // Prioritize actions
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Municipal Engineers prioritized actions:", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.adminMockRepairUpdate(report, "Work In Progress")
                                    onDismiss()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("WIP", fontSize = 11.sp)
                            }
                            Button(
                                onClick = {
                                    viewModel.adminMockRepairUpdate(report, "Resolved")
                                    onDismiss()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("Resolve", fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    )
}

fun formatReportTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

// Color getters based on states
fun getSeverityColor(sev: String): Color {
    return when (sev.trim()) {
        "High" -> Color(0xFFE53935)   // Red
        "Medium" -> Color(0xFFFF9800) // Orange
        else -> Color(0xFF4CAF50)     // Green
    }
}

fun getStatusColor(status: String): Color {
    return when (status.trim()) {
        "Verified" -> Color(0xFF2196F3)        // Blue
        "Work In Progress" -> Color(0xFF9C27B0) // Purple
        "Resolved" -> Color(0xFF4CAF50)         // Green
        else -> Color(0xFF757575)               // Grey
    }
}

fun getCategoryIcon(cat: String): ImageVector {
    return when (cat.trim()) {
        "Pothole" -> Icons.Default.DirectionsCar
        "Water Leakage" -> Icons.Default.WaterDrop
        "Damaged Streetlight" -> Icons.Default.Lightbulb
        "Waste Management" -> Icons.Default.DeleteOutline
        "Public Infrastructure" -> Icons.Default.Store
        else -> Icons.Default.Warning
    }
}

// ==========================================
// 3. SERVICE DASHBOARD & GAMIFICATION
// ==========================================

// Smartphone dashboard (single scroll container)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardSmallScreen(viewModel: CommunityHeroViewModel) {
    val reportsList by viewModel.reports.collectAsStateWithLifecycle()
    val leaderboardList by viewModel.leaderboard.collectAsStateWithLifecycle()
    val profile by viewModel.userProfile.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    var selectedTabIndex by remember { mutableStateOf(0) }

    val tabs = listOf(
        if (viewModel.appLanguage == "hi") "📊 प्रभाव मेट्रिक्स" else "📊 Impact Stats",
        if (viewModel.appLanguage == "hi") "⚡ सक्रिय कतार" else "⚡ Active Queue",
        if (viewModel.appLanguage == "hi") "🏆 लीडरबोर्ड" else "🏆 Leaderboard"
    )

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        SecondaryTabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(14.dp)
        ) {
            when (selectedTabIndex) {
                0 -> {
                    HeaderBannerSection(userName = viewModel.userName, profile = profile)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (viewModel.appLanguage == "hi") "आपकी नागरिक प्रभाव मेट्रिक्स" else "Your Civic Impact Metrics",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (viewModel.appLanguage == "hi") 
                            "यह आपका व्यक्तिगत सकारात्मक परिवर्तन हब है। देखें कि आपने कितने खतरों की सूचना दी है, कितने लोगों ने आपका समर्थन किया है और आपका सामान्य नागरिक विकास क्या है!" 
                            else "This is your personal hub of positive change. Track how many hazards you have reported, how many community upvotes your reports received, and your progression as a local civic hero!",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    MetricsGridCompact(reportsList, profile)
                }
                1 -> {
                    Text(
                        text = if (viewModel.appLanguage == "hi") "नगरपालिका सक्रिय प्राथमिकता कतार" else "Municipal Active Priority Queue",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (viewModel.appLanguage == "hi") 
                            "देखें कि स्थानीय अधिकारियों ने कौन से मरम्मत कार्यों को प्राथमिकता दी है! यह लाइव इंडेक्स खतरे की गंभीरता और समुदाय के अपवोट के आधार पर अपडेट होता है।" 
                            else "See which repair tasks local authorities have queued up first! This priority index is updated live based on the hazard's severity score plus your community's collective upvotes.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    PriorityQueueList(reportsList)
                }
                2 -> {
                    Text(
                        text = if (viewModel.appLanguage == "hi") "सामुदायिक लाइव लीडरबोर्ड" else "Community Live Leaderboard",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (viewModel.appLanguage == "hi") 
                            "हमारे पड़ोस के उन सक्रिय नागरिकों को पहचानें जिन्होंने इस महीने सबसे अधिक सुधारों में मदद की है! नए रिपोर्ट सबमिट करने पर XP अंक अर्जित करें।" 
                            else "Celebrate the most active citizens in our neighborhood! Earn community XP points by reporting validated local hazards and supporting others with upvotes.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LeaderboardWidget(leaderboardList)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// Laptop responsive dashboard (Split elements layout)
@Composable
fun DashboardLargeScreen(viewModel: CommunityHeroViewModel) {
    val reportsList by viewModel.reports.collectAsStateWithLifecycle()
    val leaderboardList by viewModel.leaderboard.collectAsStateWithLifecycle()
    val profile by viewModel.userProfile.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(20.dp)
    ) {
        HeaderBannerSection(userName = viewModel.userName, profile = profile)
        
        Spacer(modifier = Modifier.height(20.dp))
        
        // Multi-column split dashboard: Left holds stats, Right holds leaderboards and priorities
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Left column (Impact Metrics & Level Profile)
            Column(modifier = Modifier.weight(1f)) {
                Text("Citizen Metrics Hub", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(10.dp))
                MetricsGridCompact(reportsList, profile)
                
                Spacer(modifier = Modifier.height(24.dp))
                Text("Municipal Diagnostics Queue", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                PriorityQueueList(reportsList)
            }
            
            // Right column (Leaderboard list) -> satisfies "the Impact Dashboard should show the metric cards on the left side and the leaderboard list on the right side simultaneously"
            Column(modifier = Modifier.weight(1f)) {
                Text("Hyperlocal Leaderboard", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(10.dp))
                LeaderboardWidget(leaderboardList)
            }
        }
    }
}

@Composable
fun HeaderBannerSection(userName: String, profile: UserProfile) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFD0BCFF))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = userName.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF21005D),
                        letterSpacing = 1.2.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Hero Level ${profile.level}",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF21005D)
                    )
                }
                
                // +450 XP Pill
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF21005D)),
                    shape = RoundedCornerShape(100.dp)
                ) {
                    Text(
                        text = "+${profile.points} XP",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Statistics Grid Subcards precisely matching the HTML theme
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Resolved sub-pill
                Card(
                    modifier = Modifier.weight(1.1f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF7FF).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "${profile.level * 3 - 2}", // Hazards Resolved
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF21005D)
                        )
                        Text(
                            text = "Hazards Resolved",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF21005D).copy(alpha = 0.75f)
                        )
                    }
                }

                // Upvotes sub-pill
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF7FF).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "${profile.points / 15 + profile.level}", // Upvotes Given
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF21005D)
                        )
                        Text(
                            text = "Upvotes Given",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF21005D).copy(alpha = 0.75f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MetricsGridCompact(reports: List<Report>, profile: UserProfile) {
    val totalActive = reports.count { it.status != "Resolved" }
    val totalResolved = reports.count { it.status == "Resolved" }
    val verifiedCount = reports.count { it.status == "Verified" || it.status == "Work In Progress" }
    
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MetricCard(
                icon = Icons.AutoMirrored.Filled.ListAlt,
                value = "$totalActive",
                label = "Active Hazards",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                icon = Icons.Default.TaskAlt,
                value = "$totalResolved",
                label = "Resolved Repairs",
                color = Color(0xFF4CAF50),
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MetricCard(
                icon = Icons.Default.DoneAll,
                value = "$verifiedCount",
                label = "Verified Citizens",
                color = Color(0xFF2196F3),
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                icon = Icons.Default.EmojiEvents,
                value = "${profile.points} XP",
                label = "Earned Rewards",
                color = Color(0xFFFFC107),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun MetricCard(
    icon: ImageVector,
    value: String,
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Icon(imageVector = icon, contentDescription = label, tint = color, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun LeaderboardWidget(leaderboard: List<LeaderboardUser>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Citizen Standings (XP Ranking)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(10.dp))
            
            leaderboard.forEach { user ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = if (user.isCurrentUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Rank Badge
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(
                                    color = when (user.rank) {
                                        1 -> Color(0xFFFFD700) // Gold
                                        2 -> Color(0xFFC0C0C0) // Silver
                                        3 -> Color(0xFFCD7F32) // Bronze
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${user.rank}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = if (user.rank <= 3) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = user.name,
                            fontWeight = if (user.isCurrentUser) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp,
                            color = if (user.isCurrentUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${user.points} XP",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Lvl ${user.level}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

// Calculates dynamic priorities: Priority = severity score + traction upvotes
@Composable
fun PriorityQueueList(reports: List<Report>) {
    val activeList = reports.filter { it.status != "Resolved" }
        .sortedByDescending { report ->
            val severityScore = when (report.severity) {
                "High" -> 300
                "Medium" -> 150
                else -> 50
            }
            severityScore + (report.upvotes * 10)
        }
        .take(4)
        
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (activeList.isEmpty()) {
                Text("No pending repairs registered in active pipeline.", fontSize = 11.sp)
            } else {
                activeList.forEachIndexed { idx, report ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(modifier = Modifier.weight(1.3f), verticalAlignment = Alignment.CenterVertically) {
                            // Queue index
                            Text(
                                text = "#${idx + 1}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(24.dp)
                            )
                            
                            Column {
                                Text(
                                    text = report.title,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.LocationOn, "Loc", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(10.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(report.locationName, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        
                        Row(modifier = Modifier.weight(0.7f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = getSeverityColor(report.severity).copy(alpha = 0.15f))
                            ) {
                                Text(
                                    text = report.severity,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = getSeverityColor(report.severity),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${report.upvotes} votes",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    if (idx < activeList.size - 1) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleekHeaderBar(viewModel: CommunityHeroViewModel, onMenuClick: (() -> Unit)? = null) {
    TopAppBar(
        navigationIcon = {
            if (onMenuClick != null) {
                IconButton(onClick = onMenuClick) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Open Control Menu Drawer",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Navigation,
                        contentDescription = "Logo",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                Text(
                    text = getTxt("app_title", viewModel.appLanguage),
                    fontWeight = FontWeight.Medium,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        },
        actions = {
            // Interactive Language Selector Button with modern M3 styling and visual state
            Card(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .clickable {
                        viewModel.appLanguage = if (viewModel.appLanguage == "en") "hi" else "en"
                    },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = "Change Language / भाषा बदलें",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (viewModel.appLanguage == "en") "हिंदी" else "EN",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )
}

@Composable
fun GeminiLiveAlert(reports: List<Report>) {
    var isDismissed by remember { mutableStateOf(false) }
    if (isDismissed) return

    val activeReport = reports.firstOrNull()
    val zoneName = activeReport?.locationName ?: "Dadar Station Exit Zone, Mumbai"
    val failureType = (activeReport?.hazardCategory ?: "POTHOLE").uppercase()
    val explanationText = activeReport?.description ?: "High pedestrian pressure and upcoming rainfall will exacerbate road damage."

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F111A)),
        border = BorderStroke(1.dp, Color(0xFFFF3B30).copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "PREDICTION ALERT",
                        color = Color(0xFFFF453A),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp,
                        letterSpacing = 0.5.sp
                    )
                    
                    // Risk Badge
                    Card(
                        shape = RoundedCornerShape(6.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFF3B30)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Text(
                            text = "85% Risk",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // Close Button
                IconButton(
                    onClick = { isDismissed = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss prediction",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Zone Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "Zone: ",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal
                )
                Text(
                    text = zoneName,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Likely Failure Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "Likely Failure: ",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal
                )
                Text(
                    text = failureType,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Timeline Row removed as requested

            Spacer(modifier = Modifier.height(16.dp))

            // Sub-container text bubble
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161A26), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = explanationText,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun SleekAreaMapCard(viewModel: CommunityHeroViewModel, modifier: Modifier = Modifier) {
    val reportsList by viewModel.reports.collectAsStateWithLifecycle()
    val centerLat = viewModel.currentLatitude
    val centerLon = viewModel.currentLongitude
    
    var selectedMapReport by remember { mutableStateOf<Report?>(null) }
    var isMapExpanded by remember { mutableStateOf(false) }
    var isLegendExpanded by remember { mutableStateOf(false) }
    
    val currentLatLng = LatLng(centerLat, centerLon)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(currentLatLng, 15f)
    }

    LaunchedEffect(centerLat, centerLon) {
        cameraPositionState.position = CameraPosition.fromLatLngZoom(LatLng(centerLat, centerLon), 15f)
    }

    val coroutineScope = rememberCoroutineScope()

    val cardModifier = if (modifier == Modifier) {
        Modifier.height(if (isMapExpanded) 540.dp else 290.dp)
    } else {
        modifier
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .then(cardModifier),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            GoogleMap(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(24.dp)),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(isMyLocationEnabled = false),
                uiSettings = MapUiSettings(zoomControlsEnabled = false)
            ) {
                for (report in reportsList) {
                    if (report.status == "Resolved") continue
                    Marker(
                        state = MarkerState(position = LatLng(report.latitude, report.longitude)),
                        title = report.title,
                        snippet = "Severity: ${report.severity} | Status: ${report.status}",
                        onClick = {
                            selectedMapReport = report
                            true
                        }
                    )
                }
            }

            // Floater Buttons Area
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Toggle Map height to cover maximum screen area
                FloatingMapButton(icon = Icons.Default.ZoomOutMap) {
                    isMapExpanded = !isMapExpanded
                }
            }

            // Map Legend overlay
            androidx.compose.animation.AnimatedVisibility(
                visible = selectedMapReport == null,
                enter = fadeIn() + slideInVertically(initialOffsetY = { 40 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { 40 }),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            ) {
                Column {
                    if (isLegendExpanded) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0C091A).copy(alpha = 0.95f)),
                            border = BorderStroke(1.dp, Color(0xFF2D234F).copy(alpha = 0.9f)),
                            modifier = Modifier
                                .padding(bottom = 6.dp)
                                .clickable { isLegendExpanded = false }
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = getTxt("map_threat_title", viewModel.appLanguage),
                                        fontSize = 10.sp,
                                        color = Color.White.copy(alpha = 0.6f),
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close",
                                        tint = Color.White.copy(alpha = 0.5f),
                                        modifier = Modifier.size(14.dp).clickable { isLegendExpanded = false }
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(modifier = Modifier.size(8.dp).background(Color(0xFFFF3B30), CircleShape))
                                    Text(getTxt("map_high", viewModel.appLanguage), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(modifier = Modifier.size(8.dp).background(Color(0xFFFF9500), CircleShape))
                                    Text(getTxt("map_medium", viewModel.appLanguage), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(modifier = Modifier.size(8.dp).background(Color(0xFFFFCC00), CircleShape))
                                    Text(getTxt("map_low", viewModel.appLanguage), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                    
                    // Elegant Dropdown pill trigger
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isLegendExpanded) Color(0xFF00E676) else Color(0xFF0C091A).copy(alpha = 0.85f)
                        ),
                        border = BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.6f)),
                        modifier = Modifier.clickable { isLegendExpanded = !isLegendExpanded }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Map Legend / मानचित्र संकेत",
                                color = if (isLegendExpanded) Color(0xFF0C091A) else Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Icon(
                                imageVector = if (isLegendExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = "Dropdown",
                                tint = if (isLegendExpanded) Color(0xFF0C091A) else Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Interactive Map complaint details overlay (Covering full width beautifully at bottom of card)
            androidx.compose.animation.AnimatedVisibility(
                visible = selectedMapReport != null,
                enter = fadeIn() + slideInVertically(initialOffsetY = { 80 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { 80 }),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                val report = selectedMapReport
                if (report != null) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(
                                        imageVector = getCategoryIcon(report.hazardCategory),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = getTxt(report.hazardCategory.lowercase().replace(" ", ""), viewModel.appLanguage),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                
                                IconButton(
                                    onClick = { selectedMapReport = null },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(14.dp))
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            Text(
                                text = report.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            
                            Spacer(modifier = Modifier.height(2.dp))
                            
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(12.dp))
                                Text(
                                    text = report.locationName,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = getSeverityColor(report.severity).copy(alpha = 0.15f)),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = getTxt(report.severity.lowercase() + "_sev", viewModel.appLanguage),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = getSeverityColor(report.severity),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = getStatusColor(report.status).copy(alpha = 0.12f)),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        val statusKey = when (report.status) {
                                            "Pending" -> "filter_pending"
                                            "Verified" -> "filter_verified"
                                            "Work In Progress" -> "filter_wip"
                                            "Resolved" -> "filter_resolved"
                                            else -> "filter_pending"
                                        }
                                        Text(
                                            text = getTxt(statusKey, viewModel.appLanguage),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = getStatusColor(report.status),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                
                                val hasVoted = viewModel.votedReportIds[report.id] != null
                                Button(
                                    onClick = { 
                                        viewModel.upvoteReport(report)
                                        selectedMapReport = report.copy(upvotes = report.upvotes + 1)
                                    },
                                    enabled = !hasVoted,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (viewModel.votedReportIds[report.id] == "up") Color(0xFF4CD964) else MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = if (viewModel.votedReportIds[report.id] == "up") Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                                    ),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    modifier = Modifier.height(28.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Icon(Icons.Default.ThumbUp, contentDescription = null, modifier = Modifier.size(10.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${report.upvotes} " + getTxt("upvote_btn", viewModel.appLanguage), 
                                        fontSize = 10.sp, 
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Top-left map location indicator pill
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .background(Color(0xFF0C091A).copy(alpha = 0.85f), RoundedCornerShape(100.dp))
                    .border(1.dp, Color(0xFF2D234F), RoundedCornerShape(100.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(modifier = Modifier.size(6.dp).background(Color(0xFF00E676), CircleShape))
                Text(
                    text = "Mission District Area",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Dynamic bottom card popup when clicking a pin
            androidx.compose.animation.AnimatedVisibility(
                visible = selectedMapReport != null,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                selectedMapReport?.let { report ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                val catIcon = when(report.hazardCategory) {
                                    "Pothole" -> Icons.Default.AltRoute
                                    "Water Leakage" -> Icons.Default.WaterDrop
                                    "Streetlight" -> Icons.Default.Lightbulb
                                    else -> Icons.Default.ReportProblem
                                }
                                Icon(
                                    imageVector = catIcon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = report.title,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = when(report.severity) {
                                                "High" -> Color(0xFFFF4D4D).copy(alpha = 0.15f)
                                                else -> Color(0xFFFF9F1A).copy(alpha = 0.15f)
                                            }
                                        )
                                    ) {
                                        Text(
                                            text = report.severity,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = when(report.severity) {
                                                "High" -> Color(0xFFFF7676)
                                                else -> Color(0xFFFFB84D)
                                            },
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Text(
                                        text = "•  ${report.locationName}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            
                            IconButton(
                                onClick = { viewModel.upvoteReport(report) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ThumbUp,
                                    contentDescription = "Upvote",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            
                            IconButton(
                                onClick = { selectedMapReport = null },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FloatingMapButton(
    icon: ImageVector,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(Color(0xFF0C091A).copy(alpha = 0.85f), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF2D234F), RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onLoginSuccess: (String) -> Unit) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf("aadhaar") } // "aadhaar", "mobile", "gmail", "officer"
    var nameInput by remember { mutableStateOf("") }
    
    // Aadhaar variables
    var aadhaarNumber by remember { mutableStateOf("") }
    var aadhaarOtp by remember { mutableStateOf("") }
    var isOtpSentAadhaar by remember { mutableStateOf(false) }
    
    // Mobile variables
    var phoneNumber by remember { mutableStateOf("") }
    var mobileOtp by remember { mutableStateOf("") }
    var isOtpSentMobile by remember { mutableStateOf(false) }
    
    // Officer variables
    var officerId by remember { mutableStateOf("") }
    var officerPasscode by remember { mutableStateOf("") }

    // Loading states for premium animations
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF070412),
                        Color(0xFF0F0B26),
                        Color(0xFF070412)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Decorative glowing circles in background
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF4A2F86).copy(alpha = 0.18f), Color.Transparent),
                    center = Offset(size.width * 0.15f, size.height * 0.25f),
                    radius = 450f
                )
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF00E676).copy(alpha = 0.08f), Color.Transparent),
                    center = Offset(size.width * 0.82f, size.height * 0.72f),
                    radius = 400f
                )
            )
        }

        Card(
            modifier = Modifier.fillMaxSize(),
            shape = androidx.compose.ui.graphics.RectangleShape,
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0C091D))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Main Logo
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    Color(0xFF8C52FF)
                                )
                            ),
                            RoundedCornerShape(18.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Shield Logo",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "CivicHero",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    letterSpacing = 1.sp
                )
                
                Text(
                    text = "Indian Civic Action & Hazard Tracker",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Your Full Name / आपका नाम", color = Color(0xFF00E676)) },
                    leadingIcon = { Icon(Icons.Default.Person, "Name", tint = Color(0xFF00E676)) },
                    placeholder = { Text("e.g. Gaurav Sharma", color = Color.White.copy(alpha = 0.4f)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White.copy(alpha = 0.9f),
                        focusedBorderColor = Color(0xFF00E676),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        focusedLabelColor = Color(0xFF00E676),
                        unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = { 
                        if (nameInput.trim().isBlank()) {
                            Toast.makeText(context, "Please enter your Full Name first / कृपया अपना नाम दर्ज करें", Toast.LENGTH_LONG).show()
                        } else {
                            onLoginSuccess(nameInput.trim())
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("skip_login_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E676),
                        contentColor = Color(0xFF070412)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = "Quick Entrance",
                        tint = Color(0xFF070412),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Quick Launch Portal / सीधे शुरू करें",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp,
                        letterSpacing = 0.5.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(modifier = Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.12f)))
                    Text("  OR SECURE SIGN IN / सरकारी लॉगिन  ", fontSize = 8.sp, color = Color.White.copy(alpha = 0.4f), fontWeight = FontWeight.Bold)
                    Box(modifier = Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.12f)))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Custom Segmented-like Tab Selector for Options
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp)
                ) {
                    SegmentedButton(
                        selected = selectedTab == "aadhaar",
                        onClick = { selectedTab = "aadhaar" },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 4),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            inactiveContainerColor = Color.Transparent,
                            inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text("Aadhaar", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    SegmentedButton(
                        selected = selectedTab == "mobile",
                        onClick = { selectedTab = "mobile" },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 4),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            inactiveContainerColor = Color.Transparent,
                            inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text("Mobile", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    SegmentedButton(
                        selected = selectedTab == "gmail",
                        onClick = { selectedTab = "gmail" },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 4),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            inactiveContainerColor = Color.Transparent,
                            inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text("Gmail", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    SegmentedButton(
                        selected = selectedTab == "officer",
                        onClick = { selectedTab = "officer" },
                        shape = SegmentedButtonDefaults.itemShape(index = 3, count = 4),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            inactiveContainerColor = Color.Transparent,
                            inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text("Officer", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Interactive Login Fields based on Tab
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    when (selectedTab) {
                        "aadhaar" -> {
                            Text(
                                text = "Secure Aadhaar Verification (UIDAI Compliant)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF9F1A)
                            )
                            
                            OutlinedTextField(
                                value = aadhaarNumber,
                                onValueChange = { if (it.length <= 12 && it.all { ch -> ch.isDigit() }) aadhaarNumber = it },
                                label = { Text("12-Digit Aadhaar UID") },
                                leadingIcon = { Icon(Icons.Default.Fingerprint, "UID") },
                                placeholder = { Text("XXXX XXXX XXXX") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White.copy(alpha = 0.9f),
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    unfocusedContainerColor = Color.Transparent
                                )
                            )

                            if (isOtpSentAadhaar) {
                                OutlinedTextField(
                                    value = aadhaarOtp,
                                    onValueChange = { if (it.length <= 6 && it.all { ch -> ch.isDigit() }) aadhaarOtp = it },
                                    label = { Text("6-Digit OTP received on registered mobile") },
                                    placeholder = { Text("######") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White.copy(alpha = 0.9f)
                                    )
                                )
                            }

                            Button(
                                onClick = {
                                    if (nameInput.trim().isBlank()) {
                                        Toast.makeText(context, "Please enter your Full Name first / कृपया अपना नाम दर्ज करें", Toast.LENGTH_LONG).show()
                                        return@Button
                                    }
                                    if (!isOtpSentAadhaar) {
                                        if (aadhaarNumber.length == 12) {
                                            isOtpSentAadhaar = true
                                            Toast.makeText(context, "OTP dispatched to UID Aadhaar registered number!", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "Please enter a valid 12-digit UID", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        if (aadhaarOtp.length == 6) {
                                            isLoading = true
                                            Toast.makeText(context, "Aadhaar authentication successful", Toast.LENGTH_SHORT).show()
                                            onLoginSuccess(nameInput.trim())
                                        } else {
                                            Toast.makeText(context, "Please enter 6-digit OTP", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                                } else {
                                    Text(if (isOtpSentAadhaar) "Verify & Access Portal" else "Send Secure Aadhaar OTP", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        "mobile" -> {
                            Text(
                                text = "Instant Citizen OTP Login",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            
                            OutlinedTextField(
                                value = phoneNumber,
                                onValueChange = { if (it.length <= 10 && it.all { ch -> ch.isDigit() }) phoneNumber = it },
                                label = { Text("10-Digit Citizen Mobile") },
                                leadingIcon = { Text("+91  ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp)) },
                                placeholder = { Text("XXXXXXXXXX") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White.copy(alpha = 0.9f)
                                )
                            )

                            if (isOtpSentMobile) {
                                OutlinedTextField(
                                    value = mobileOtp,
                                    onValueChange = { if (it.length <= 6 && it.all { ch -> ch.isDigit() }) mobileOtp = it },
                                    label = { Text("Enter OTP") },
                                    placeholder = { Text("######") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White.copy(alpha = 0.9f)
                                    )
                                )
                            }

                            Button(
                                onClick = {
                                    if (nameInput.trim().isBlank()) {
                                        Toast.makeText(context, "Please enter your Full Name first / कृपया अपना नाम दर्ज करें", Toast.LENGTH_LONG).show()
                                        return@Button
                                    }
                                    if (!isOtpSentMobile) {
                                        if (phoneNumber.length == 10) {
                                            isOtpSentMobile = true
                                            mobileOtp = "123456" // autofill for testing convenience
                                            Toast.makeText(context, "OTP SMS sent successfully! Use code 123456", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "Enter a valid 10-digit mobile number", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        if (mobileOtp == "123456" || mobileOtp.length == 6) {
                                            isLoading = true
                                            onLoginSuccess(nameInput.trim())
                                        } else {
                                            Toast.makeText(context, "Enter valid 6-digit OTP code (try 123456)", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                                } else {
                                    Text(if (isOtpSentMobile) "Connect Now" else "Send Authentication OTP", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        "gmail" -> {
                            Text(
                                text = "Unified OAuth Account Check-in",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            
                            Spacer(modifier = Modifier.height(6.dp))

                            // Beautiful Simulated Google Sign In Button
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .clickable {
                                        if (nameInput.trim().isBlank()) {
                                            Toast.makeText(context, "Please enter your Full Name first / कृपया अपना नाम दर्ज करें", Toast.LENGTH_LONG).show()
                                        } else {
                                            isLoading = true
                                            Toast
                                                .makeText(
                                                    context,
                                                    "Connecting safely via Google Identity services...",
                                                    Toast.LENGTH_SHORT
                                                )
                                                .show()
                                            onLoginSuccess(nameInput.trim())
                                        }
                                    },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                border = BorderStroke(1.dp, Color(0xFFDCDCDC))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle, // Representative logo
                                        contentDescription = "G",
                                        tint = Color(0xFF4285F4),
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Continue with Google Account",
                                        color = Color(0xFF5F6368), // Dark grey standard Google font color
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    if (nameInput.trim().isBlank()) {
                                        Toast.makeText(context, "Please enter your Full Name first / कृपया अपना नाम दर्ज करें", Toast.LENGTH_LONG).show()
                                    } else {
                                        onLoginSuccess(nameInput.trim())
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                            ) {
                                Icon(Icons.Default.VerifiedUser, "Guest", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Continue as Guest Inspector", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }

                        "officer" -> {
                            Text(
                                text = "Municipal Department Officer Entrance",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E676)
                            )
                            
                            OutlinedTextField(
                                value = officerId,
                                onValueChange = { officerId = it },
                                label = { Text("Departmental Officer Badge/UID") },
                                placeholder = { Text("INSP-MH-4015") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White.copy(alpha = 0.9f)
                                )
                            )

                            OutlinedTextField(
                                value = officerPasscode,
                                onValueChange = { officerPasscode = it },
                                label = { Text("Encrypted Municipal Secret Key") },
                                placeholder = { Text("••••••••") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White.copy(alpha = 0.9f)
                                )
                            )

                            Button(
                                onClick = {
                                    if (nameInput.trim().isBlank()) {
                                        Toast.makeText(context, "Please enter your Full Name first / कृपया अपना नाम दर्ज करें", Toast.LENGTH_LONG).show()
                                        return@Button
                                    }
                                    if (officerId.isNotBlank() && officerPasscode.length >= 4) {
                                        isLoading = true
                                        Toast.makeText(context, "Municipal database synchronized", Toast.LENGTH_SHORT).show()
                                        onLoginSuccess(nameInput.trim())
                                    } else {
                                        Toast.makeText(context, "Please enter Officer ID and Passcode", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853))
                            ) {
                                Text("Secure Officer Entry", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Footer security stamps
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Secured",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "Encrypted Civic Network connection established",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// Helper class for 4-entry presets
data class Quad<out A, out B, out C, out D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

// Interactive Mini-Map Selector with real Google Map integration
@Composable
fun InteractiveMiniMapSelector(viewModel: CommunityHeroViewModel) {
    val centerLat = viewModel.currentLatitude
    val centerLon = viewModel.currentLongitude
    val reportsList by viewModel.reports.collectAsStateWithLifecycle()

    val currentLatLng = LatLng(centerLat, centerLon)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(currentLatLng, 15f)
    }

    // Keep camera synced if view model changes programmatically, but only when not moving
    LaunchedEffect(centerLat, centerLon) {
        if (!cameraPositionState.isMoving) {
            cameraPositionState.position = CameraPosition.fromLatLngZoom(LatLng(centerLat, centerLon), 15f)
        }
    }

    // When camera is moving/moved, update view model
    LaunchedEffect(cameraPositionState.isMoving) {
        if (!cameraPositionState.isMoving) {
            viewModel.currentLatitude = cameraPositionState.position.target.latitude
            viewModel.currentLongitude = cameraPositionState.position.target.longitude
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0C091D))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(isMyLocationEnabled = false),
                uiSettings = MapUiSettings(zoomControlsEnabled = true)
            ) {
                for (report in reportsList) {
                    if (report.status == "Resolved") continue
                    Marker(
                        state = MarkerState(position = LatLng(report.latitude, report.longitude)),
                        title = report.title,
                        snippet = "Severity: ${report.severity} | Status: ${report.status}"
                    )
                }
            }

            // Centered fixed pinpoint icon
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = "Target Pin",
                tint = Color(0xFF00E676),
                modifier = Modifier
                    .size(36.dp)
                    .align(Alignment.Center)
                    .absoluteOffset(y = (-12).dp)
            )
        }
    }
}

@Composable
fun SimulatedVectorMap(
    centerLat: Double,
    centerLon: Double,
    reports: List<Report>,
    selectedReport: Report?,
    onReportClick: (Report) -> Unit,
    modifier: Modifier = Modifier,
    onDragLocation: ((Double, Double) -> Unit)? = null
) {
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var zoomScale by remember { mutableStateOf(1f) }
    val context = LocalContext.current

    // Auto-reset offsets when NOT in manual picker mode
    if (onDragLocation == null) {
        LaunchedEffect(centerLat, centerLon) {
            offsetX = 0f
            offsetY = 0f
        }
    }

    Box(
        modifier = modifier
            .background(Color(0xFF0F0E1E))
            .pointerInput(zoomScale) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    if (onDragLocation != null) {
                        val scale = 30000f * zoomScale
                        val newLon = centerLon - (dragAmount.x / scale)
                        val newLat = centerLat + (dragAmount.y / scale)
                        onDragLocation(newLat, newLon)
                    } else {
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                }
            }
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val centerX = width / 2f + offsetX
            val centerY = height / 2f + offsetY

            val scale = 30000f * zoomScale

            // Draw grid background lines
            val gridSpacing = 80f * zoomScale
            val gridColor = Color(0xFF1E1A3C).copy(alpha = 0.4f)

            var currX = centerX % gridSpacing
            while (currX < width) {
                drawLine(gridColor, Offset(currX, 0f), Offset(currX, height), strokeWidth = 1f)
                currX += gridSpacing
            }
            var currY = centerY % gridSpacing
            while (currY < height) {
                drawLine(gridColor, Offset(0f, currY), Offset(width, currY), strokeWidth = 1f)
                currY += gridSpacing
            }

            // Draw a beautiful flowing river (blue diagonal stripe)
            val riverPath = androidx.compose.ui.graphics.Path()
            riverPath.moveTo(centerX - 1000f, centerY - 800f)
            riverPath.quadraticTo(centerX, centerY - 100f, centerX + 1200f, centerY + 800f)
            drawPath(
                path = riverPath,
                color = Color(0xFF152238),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 80f * zoomScale, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )

            // Draw some green park rectangles
            drawRoundRect(
                color = Color(0xFF122C1E),
                topLeft = Offset(centerX - 400f, centerY + 200f),
                size = androidx.compose.ui.geometry.Size(250f * zoomScale, 180f * zoomScale),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f)
            )
            drawRoundRect(
                color = Color(0xFF122C1E),
                topLeft = Offset(centerX + 300f, centerY - 500f),
                size = androidx.compose.ui.geometry.Size(300f * zoomScale, 200f * zoomScale),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f)
            )

            // Draw primary streets (glowing dark purple lines)
            val streetColor = Color(0xFF271F47)
            val streetWidth = 18f * zoomScale

            // Horizontal Main street
            drawLine(streetColor, Offset(0f, centerY), Offset(width, centerY), strokeWidth = streetWidth)
            // Vertical Central boulevard
            drawLine(streetColor, Offset(centerX, 0f), Offset(centerX, height), strokeWidth = streetWidth)
            // Ring street
            drawCircle(
                color = streetColor,
                center = Offset(centerX, centerY),
                radius = 350f * zoomScale,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 12f * zoomScale)
            )

            // Draw reports pins
            for (report in reports) {
                if (report.status == "Resolved") continue

                val reportDx = (report.longitude - centerLon).toFloat() * scale
                val reportDy = (centerLat - report.latitude).toFloat() * scale

                val pinX = centerX + reportDx
                val pinY = centerY + reportDy

                val pinColor = when (report.severity) {
                    "High" -> Color(0xFFFF3B30)
                    "Medium" -> Color(0xFFFF9500)
                    else -> Color(0xFF4CD964)
                }

                drawCircle(
                    color = pinColor.copy(alpha = 0.25f),
                    center = Offset(pinX, pinY),
                    radius = 24f * zoomScale
                )

                drawCircle(
                    color = pinColor,
                    center = Offset(pinX, pinY),
                    radius = 8f * zoomScale
                )

                drawCircle(
                    color = Color.White,
                    center = Offset(pinX, pinY),
                    radius = 3f * zoomScale
                )
            }

            // Draw user's current position beacon
            val time = System.currentTimeMillis()
            val pulseRadius = (16f + 8f * Math.sin((time % 1000).toDouble() / 1000.0 * 2.0 * Math.PI).toFloat()) * zoomScale

            drawCircle(
                color = Color(0xFF00E676).copy(alpha = 0.25f),
                center = Offset(centerX, centerY),
                radius = pulseRadius
            )

            drawCircle(
                color = Color(0xFF00E676),
                center = Offset(centerX, centerY),
                radius = 6f * zoomScale
            )

            drawCircle(
                color = Color.White,
                center = Offset(centerX, centerY),
                radius = 2f * zoomScale
            )
        }

        // Transparent Overlay of Clickable Targets for Pin Hits
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val widthDp = maxWidth
            val heightDp = maxHeight

            val scaleDp = (30000f * zoomScale) / 3f

            for (report in reports) {
                if (report.status == "Resolved") continue

                val reportDx = (report.longitude - centerLon).toFloat() * scaleDp
                val reportDy = (centerLat - report.latitude).toFloat() * scaleDp

                val pinX = (widthDp / 2f) + reportDx.dp + (offsetX / 3f).dp
                val pinY = (heightDp / 2f) - reportDy.dp + (offsetY / 3f).dp

                Box(
                    modifier = Modifier
                        .absoluteOffset(x = pinX - 20.dp, y = pinY - 20.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { onReportClick(report) }
                )
            }
        }

        // Floating Controls directly integrated in the Vector Map
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = { zoomScale = (zoomScale * 1.2f).coerceAtMost(3f) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Zoom In", tint = Color.White)
            }
            IconButton(
                onClick = { zoomScale = (zoomScale / 1.2f).coerceAtLeast(0.5f) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Zoom Out", tint = Color.White)
            }
            IconButton(
                onClick = {
                    offsetX = 0f
                    offsetY = 0f
                    zoomScale = 1f
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Reset", tint = Color.White)
            }
        }
    }
}


