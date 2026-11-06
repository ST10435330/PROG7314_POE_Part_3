package com.studysync.app

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private data class StudyDestination(val screen: Int, val label: Int, val icon: Int)

// Keep the existing screen IDs while placing Focus and Resources beside the study tabs.
private val destinations = listOf(
    StudyDestination(0, R.string.tasks, R.drawable.ic_nav_tasks),
    StudyDestination(1, R.string.subjects, R.drawable.ic_nav_subjects),
    StudyDestination(3, R.string.focus_tab, R.drawable.ic_nav_focus),
    StudyDestination(4, R.string.ui_nav_resources, R.drawable.ic_nav_resources),
    StudyDestination(2, R.string.settings, R.drawable.ic_nav_settings)
)

// Reference: https://developer.android.com/develop/ui/compose/components/navigation-bar
@Composable
internal fun StudyBottomNavigation(selected: Int, enabled: Boolean, onSelected: (Int) -> Unit) {
    val context = LocalContext.current
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        destinations.forEach { destination ->
            NavigationBarItem(
                selected = selected == destination.screen,
                enabled = enabled,
                onClick = { onSelected(destination.screen) },
                icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                label = {
                    Text(context.getString(destination.label), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            )
        }
    }
}
