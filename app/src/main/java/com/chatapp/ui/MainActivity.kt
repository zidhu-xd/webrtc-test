package com.chatapp.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.chatapp.ui.auth.LoginScreen
import com.chatapp.ui.auth.RegisterScreen
import com.chatapp.ui.call.CallScreen
import com.chatapp.ui.chat.ChatScreen
import com.chatapp.ui.contacts.ContactsScreen
import com.chatapp.ui.conversations.ConversationsScreen
import com.chatapp.ui.theme.ChatAppTheme
import com.chatapp.viewmodel.AuthViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Request camera and microphone permissions (needed for calls)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Permissions handled gracefully - calls require camera/mic but chat doesn't
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request permissions on startup
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        )

        setContent {
            ChatAppTheme {
                ChatAppNavigation()
            }
        }
    }
}

/**
 * App Navigation
 * Routes:
 *   login       -> Login screen
 *   register    -> Register screen
 *   home        -> Conversations list (main screen)
 *   chat/{id}   -> Chat screen for a specific conversation
 *   contacts    -> User search / contacts list
 *   call/{id}   -> Active call screen
 */
@Composable
fun ChatAppNavigation() {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = hiltViewModel()
    val token by authViewModel.authToken.collectAsState(initial = null)

    // Determine start destination based on auth state
    val startDestination = if (token != null) "home" else "login"

    NavHost(navController = navController, startDestination = startDestination) {

        composable("login") {
            LoginScreen(
                onLoginSuccess = { navController.navigate("home") { popUpTo("login") { inclusive = true } } },
                onNavigateToRegister = { navController.navigate("register") }
            )
        }

        composable("register") {
            RegisterScreen(
                onRegisterSuccess = { navController.navigate("home") { popUpTo("register") { inclusive = true } } },
                onNavigateToLogin = { navController.popBackStack() }
            )
        }

        composable("home") {
            ConversationsScreen(
                onConversationClick = { conversationId ->
                    navController.navigate("chat/$conversationId")
                },
                onNewChatClick = { navController.navigate("contacts") },
                onLogout = {
                    navController.navigate("login") { popUpTo("home") { inclusive = true } }
                }
            )
        }

        composable("contacts") {
            ContactsScreen(
                onUserSelected = { conversationId ->
                    navController.navigate("chat/$conversationId") {
                        popUpTo("contacts") { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            "chat/{conversationId}",
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType })
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getString("conversationId") ?: ""
            ChatScreen(
                conversationId = conversationId,
                onBack = { navController.popBackStack() },
                onCallClick = { userId, name, isVideo ->
                    navController.navigate("call/$userId?name=$name&video=$isVideo")
                }
            )
        }

        composable(
            "call/{userId}?name={name}&video={isVideo}",
            arguments = listOf(
                navArgument("userId") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType; defaultValue = "" },
                navArgument("isVideo") { type = NavType.BoolType; defaultValue = false }
            )
        ) { backStackEntry ->
            CallScreen(
                remoteUserId = backStackEntry.arguments?.getString("userId") ?: "",
                remoteName = backStackEntry.arguments?.getString("name") ?: "",
                isVideoCall = backStackEntry.arguments?.getBoolean("isVideo") ?: false,
                onCallEnd = { navController.popBackStack() }
            )
        }
    }
}
