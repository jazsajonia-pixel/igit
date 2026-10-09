package app.igit.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun IgitBottomBar(selected: AppTab, onSelect: (AppTab) -> Unit) {
    NavigationBar(containerColor = Color(0xFF191A1A), contentColor = Color.White) {
        AppTab.entries.forEach { tab ->
            val icon = when (tab) {
                AppTab.CHAT -> Icons.Default.ChatBubbleOutline
                AppTab.HOME -> Icons.Default.AccountTree
                AppTab.FILES -> Icons.Default.Folder
            }
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(icon, contentDescription = tab.title) },
                label = { Text(tab.title) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF80C7FF),
                    selectedTextColor = Color(0xFF80C7FF),
                    indicatorColor = Color(0xFF26292B),
                    unselectedIconColor = Color(0xFF9CA2A6),
                    unselectedTextColor = Color(0xFF9CA2A6)
                )
            )
        }
    }
}
