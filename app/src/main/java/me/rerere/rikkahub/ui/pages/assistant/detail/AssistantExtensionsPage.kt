package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.ai.LorebooksContent
import me.rerere.rikkahub.ui.components.ai.PromptPresetsContent
import me.rerere.rikkahub.ui.components.ai.QuickMessagesContent
import me.rerere.rikkahub.ui.components.ai.SkillsContent
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ExtensionTab
import me.rerere.rikkahub.ui.components.ui.PagerTabs
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantExtensionsPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val skills by vm.skills.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pagerState = rememberPagerState { ExtensionTab.entries.size }
    val listPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    // 页面背景比 sheet 深，列表项用更亮的容器色才分得开
    val listColors = ListItemDefaults.segmentedColors(
        containerColor = MaterialTheme.colorScheme.surfaceBright,
    )

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_extensions_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PagerTabs(
                pagerState = pagerState,
                tabs = ExtensionTab.entries,
                modifier = Modifier.padding(bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                when (val tab = ExtensionTab.entries[page]) {
                    ExtensionTab.QUICK_MESSAGES -> {
                        if (settings.quickMessages.isEmpty()) {
                            ExtensionEmptyState(
                                message = stringResource(R.string.assistant_extensions_page_empty_quick_messages),
                                buttonText = stringResource(R.string.assistant_extensions_page_goto_extensions),
                                onAction = { navController.navigate(Screen.QuickMessages()) },
                                icon = tab.icon,
                            )
                        } else {
                            QuickMessagesContent(
                                contentPadding = listPadding,
                                colors = listColors,
                                quickMessages = settings.quickMessages,
                                selectedIds = assistant.quickMessageIds,
                                onToggle = { quickMessageId, checked ->
                                    val newIds = if (checked) assistant.quickMessageIds + quickMessageId
                                    else assistant.quickMessageIds - quickMessageId
                                    vm.update(assistant.copy(quickMessageIds = newIds))
                                },
                                onItemClick = { quickMessage ->
                                    navController.navigate(
                                        Screen.QuickMessages(quickMessageId = quickMessage.id.toString())
                                    )
                                },
                                onManage = { navController.navigate(Screen.QuickMessages()) },
                            )
                        }
                    }

                    ExtensionTab.MODE_INJECTIONS -> {
                        if (settings.promptPresets.isEmpty()) {
                            ExtensionEmptyState(
                                message = "暂无提示词预设",
                                buttonText = stringResource(R.string.assistant_extensions_page_goto_prompts),
                                onAction = { navController.navigate(Screen.Prompts(initialTab = 0)) },
                                icon = tab.icon,
                            )
                        } else {
                            PromptPresetsContent(
                                contentPadding = listPadding,
                                colors = listColors,
                                promptPresets = settings.promptPresets,
                                selectedIds = assistant.modeInjectionIds,
                                onToggle = { presetId, checked ->
                                    val newIds = if (checked) {
                                        (assistant.modeInjectionIds - settings.promptPresets.map { it.id }.toSet()) + presetId
                                    } else {
                                        assistant.modeInjectionIds - presetId
                                    }
                                    vm.update(assistant.copy(modeInjectionIds = newIds))
                                },
                                onItemClick = { preset ->
                                    navController.navigate(
                                        Screen.Prompts(
                                            initialTab = 0,
                                            presetId = preset.id.toString(),
                                        )
                                    )
                                },
                                onManage = { navController.navigate(Screen.Prompts(initialTab = 0)) },
                            )
                        }
                    }

                    ExtensionTab.LOREBOOKS -> {
                        if (settings.lorebooks.isEmpty()) {
                            ExtensionEmptyState(
                                message = stringResource(R.string.assistant_extensions_page_empty_lorebooks),
                                buttonText = stringResource(R.string.assistant_extensions_page_goto_prompts),
                                onAction = { navController.navigate(Screen.Prompts(initialTab = 1)) },
                                icon = tab.icon,
                            )
                        } else {
                            LorebooksContent(
                                contentPadding = listPadding,
                                colors = listColors,
                                lorebooks = settings.lorebooks,
                                selectedIds = assistant.lorebookIds,
                                onToggle = { injId, checked ->
                                    val newIds = if (checked) assistant.lorebookIds + injId
                                    else assistant.lorebookIds - injId
                                    vm.update(assistant.copy(lorebookIds = newIds))
                                },
                                onItemClick = { lorebook ->
                                    navController.navigate(
                                        Screen.Prompts(
                                            initialTab = 1,
                                            lorebookId = lorebook.id.toString(),
                                        )
                                    )
                                },
                                onManage = { navController.navigate(Screen.Prompts(initialTab = 1)) },
                            )
                        }
                    }

                    ExtensionTab.SKILLS -> {
                        if (skills.isEmpty()) {
                            ExtensionEmptyState(
                                message = stringResource(R.string.assistant_extensions_page_empty_skills),
                                buttonText = stringResource(R.string.assistant_extensions_page_goto_extensions),
                                onAction = { navController.navigate(Screen.Skills) },
                                icon = tab.icon,
                            )
                        } else {
                            SkillsContent(
                                contentPadding = listPadding,
                                colors = listColors,
                                skills = skills,
                                enabledSkills = assistant.enabledSkills,
                                onToggle = { name, checked ->
                                    val newSkills = if (checked) assistant.enabledSkills + name
                                    else assistant.enabledSkills - name
                                    vm.update(assistant.copy(enabledSkills = newSkills))
                                },
                                onItemClick = { skill ->
                                    navController.navigate(Screen.SkillDetail(skill.name))
                                },
                                onManage = { navController.navigate(Screen.Skills) },
                            )
                        }
                    }
                }
            }
        }
    }
}
