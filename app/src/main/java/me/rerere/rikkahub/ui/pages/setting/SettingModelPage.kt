package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun SettingModelPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_model_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor
            ) {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    icon = { Icon(HugeIcons.AiBrain01, null) },
                    label = { Text(stringResource(R.string.setting_model_page_tab_model)) }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    icon = { Icon(HugeIcons.AiEditing, null) },
                    label = { Text(stringResource(R.string.setting_model_page_tab_prompt)) }
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { contentPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> ModelSettingsPage(settings = settings, vm = vm, contentPadding = contentPadding)
                1 -> PromptSettingsPage(settings = settings, vm = vm, contentPadding = contentPadding)
            }
        }
    }
}

@Composable
private fun ModelSettingsPage(settings: Settings, vm: SettingVM, contentPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_chat_model),
                description = stringResource(R.string.setting_model_page_chat_model_desc),
                modelId = settings.chatModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(chatModelId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_fast_model),
                description = stringResource(R.string.setting_model_page_fast_model_desc),
                modelId = settings.fastModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(fastModelId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_title_model),
                description = stringResource(R.string.setting_model_page_title_model_desc),
                modelId = settings.titleModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(titleModelId = it.id)) },
                onClear = { vm.updateSettings(settings.copy(titleModelId = null)) },
            )
        }
        item {
            SuggestionModelSettingItem(
                settings = settings,
                vm = vm,
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_translate_model),
                description = stringResource(R.string.setting_model_page_translate_model_desc),
                modelId = settings.translateModeId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(translateModeId = it.id)) },
            )
        }
        item {
            OcrModelSettingItem(
                settings = settings,
                vm = vm,
            )
        }
        item {
            CompressModelSettingItem(
                settings = settings,
                vm = vm,
            )
        }
    }
}

@Composable
private fun SuggestionModelSettingItem(
    settings: Settings,
    vm: SettingVM,
) {
    val title = stringResource(R.string.setting_model_page_suggestion_model)
    val state = rememberModelListState(
        modelId = settings.suggestionModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )

    Column {
        CardGroup(title = { Text(title) }) {
            item(
                headlineContent = { Text(stringResource(R.string.setting_model_page_enable_suggestion)) },
                trailingContent = {
                    Switch(
                        checked = settings.enableSuggestion,
                        onCheckedChange = {
                            vm.updateSettings(settings.copy(enableSuggestion = it))
                        }
                    )
                },
            )
            if (settings.enableSuggestion) {
                item(
                    onClick = { state.open() },
                    headlineContent = { Text(title) },
                    trailingContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = state.currentModel?.displayName
                                    ?: stringResource(R.string.model_list_select_model),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (state.currentModel != null) {
                                IconButton(
                                    onClick = { vm.updateSettings(settings.copy(suggestionModelId = null)) },
                                    modifier = Modifier.size(20.dp),
                                ) {
                                    Icon(HugeIcons.Cancel01, contentDescription = null, modifier = Modifier.size(14.dp))
                                }
                            } else {
                                Icon(
                                    HugeIcons.ArrowRight01,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    },
                )
            }
        }
        Text(
            text = stringResource(R.string.setting_model_page_suggestion_model_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }

    ModelListSheet(state = state, onSelect = { vm.updateSettings(settings.copy(suggestionModelId = it.id)) })
}

@Composable
private fun ModelSettingItem(
    title: String,
    description: String,
    modelId: Uuid?,
    providers: List<ProviderSetting>,
    onSelect: (Model) -> Unit,
    onClear: (() -> Unit)? = null,
) {
    val state = rememberModelListState(
        modelId = modelId,
        providers = providers,
        type = ModelType.CHAT,
    )

    Column {
        CardGroup(title = { Text(title) }) {
            item(
                onClick = { state.open() },
                headlineContent = { Text(title) },
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = state.currentModel?.displayName
                                ?: stringResource(R.string.model_list_select_model),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (onClear != null && state.currentModel != null) {
                            IconButton(onClick = onClear, modifier = Modifier.size(20.dp)) {
                                Icon(HugeIcons.Cancel01, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        } else {
                            Icon(
                                HugeIcons.ArrowRight01,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                },
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }

    ModelListSheet(state = state, onSelect = onSelect)
}

/**
 * Port of jude's OCR / compression "separate OpenAI-compatible endpoint" sections
 * (SettingModelPage.kt :616-764 / :791-894), adapted to this page's CardGroup +
 * ModelListSheet idiom. When [enabled] is off the row behaves exactly like the
 * pre-existing [ModelSettingItem] (main provider list selection); when on, the
 * model is picked from the separate endpoint and the connection fields live in a
 * bottom-sheet config form.
 */
@Composable
private fun SeparateApiModelSettingItem(
    title: String,
    description: String,
    sheetTitle: String,
    sheetDescription: String,
    providerName: String,
    enabled: Boolean,
    modelId: String,
    apiKey: String,
    baseUrl: String,
    chatCompletionsPath: String,
    useResponseApi: Boolean,
    fallbackModelId: Uuid,
    fallbackProviders: List<ProviderSetting>,
    onEnabledChange: (Boolean) -> Unit,
    onConfigUpdate: (
        enabled: Boolean,
        modelId: String,
        apiKey: String,
        baseUrl: String,
        chatCompletionsPath: String,
        useResponseApi: Boolean,
    ) -> Unit,
    onFallbackModelSelect: (Model) -> Unit,
) {
    var showConfigSheet by remember { mutableStateOf(false) }
    val fallbackState = rememberModelListState(
        modelId = fallbackModelId,
        providers = fallbackProviders,
        type = ModelType.CHAT,
    )
    // Synthetic provider for the separate endpoint (jude SeparateOpenAIModelSelector
    // semantics): models are fetched from the endpoint's /models route whenever the
    // connection fields change and the override is enabled. The previously chosen id
    // stays selectable as a synthetic fallback entry when the fetch fails or omits it.
    val providerManager = koinInject<ProviderManager>()
    val separateProvider = remember(apiKey, baseUrl, chatCompletionsPath, useResponseApi) {
        ProviderSetting.OpenAI(
            name = providerName,
            apiKey = apiKey,
            baseUrl = baseUrl.trimEnd('/'),
            chatCompletionsPath = chatCompletionsPath,
            useResponseApi = useResponseApi,
        )
    }
    val separateModels by produceState(
        initialValue = emptyList<Model>(),
        key1 = separateProvider,
        key2 = enabled,
    ) {
        if (!enabled) {
            value = emptyList()
            return@produceState
        }
        value = runCatching {
            providerManager.getProviderByType(separateProvider)
                .listModels(separateProvider)
                .map { it.copy(type = ModelType.CHAT) }
                .sortedBy { it.modelId }
        }.onFailure {
            it.printStackTrace()
        }.getOrDefault(emptyList())
    }
    val separateModelList = remember(separateModels, modelId) {
        val selectedFallback = modelId
            .takeIf { it.isNotBlank() && separateModels.none { model -> model.modelId == it } }
            ?.let { Model(modelId = it, displayName = it, type = ModelType.CHAT) }
        if (selectedFallback == null) separateModels else listOf(selectedFallback) + separateModels
    }
    val separateState = rememberModelListState(
        modelId = separateModelList.firstOrNull { it.modelId == modelId }?.id,
        providers = listOf(separateProvider.copy(models = separateModelList)),
        type = ModelType.CHAT,
    )

    Column {
        CardGroup(title = { Text(title) }) {
            item(
                headlineContent = { Text(stringResource(R.string.setting_model_page_separate_api_enable)) },
                supportingContent = { Text(sheetTitle) },
                trailingContent = {
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                },
            )
            if (enabled) {
                item(
                    onClick = { separateState.open() },
                    headlineContent = { Text(stringResource(R.string.setting_model_page_separate_api_model)) },
                    supportingContent = {
                        Text(
                            text = modelId.ifBlank { stringResource(R.string.model_list_select_model) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        Icon(HugeIcons.ArrowRight01, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                )
                item(
                    onClick = { showConfigSheet = true },
                    headlineContent = { Text(stringResource(R.string.setting_model_page_separate_api_config)) },
                    supportingContent = {
                        Text(
                            text = baseUrl,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        Icon(HugeIcons.ArrowRight01, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                )
            } else {
                item(
                    onClick = { fallbackState.open() },
                    headlineContent = { Text(title) },
                    trailingContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = fallbackState.currentModel?.displayName
                                    ?: stringResource(R.string.model_list_select_model),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(HugeIcons.ArrowRight01, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    },
                )
            }
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }

    ModelListSheet(state = fallbackState, onSelect = onFallbackModelSelect)
    ModelListSheet(state = separateState, onSelect = { model ->
        onConfigUpdate(enabled, model.modelId, apiKey, baseUrl, chatCompletionsPath, useResponseApi)
    })

    if (showConfigSheet) {
        ModalBottomSheet(
            onDismissRequest = { showConfigSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SeparateApiConfigForm(
                    title = sheetTitle,
                    description = sheetDescription,
                    enabled = enabled,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    chatCompletionsPath = chatCompletionsPath,
                    useResponseApi = useResponseApi,
                    onEnabledChange = onEnabledChange,
                    onApiKeyChange = {
                        onConfigUpdate(enabled, modelId, it, baseUrl, chatCompletionsPath, useResponseApi)
                    },
                    onBaseUrlChange = {
                        onConfigUpdate(enabled, modelId, apiKey, it, chatCompletionsPath, useResponseApi)
                    },
                    onChatCompletionsPathChange = {
                        onConfigUpdate(enabled, modelId, apiKey, baseUrl, it, useResponseApi)
                    },
                    onUseResponseApiChange = {
                        onConfigUpdate(enabled, modelId, apiKey, baseUrl, chatCompletionsPath, it)
                    },
                )
            }
        }
    }
}

@Composable
private fun OcrModelSettingItem(settings: Settings, vm: SettingVM) {
    val config = settings.ocrOpenAIConfig
    SeparateApiModelSettingItem(
        title = stringResource(R.string.setting_model_page_ocr_model),
        description = stringResource(R.string.setting_model_page_ocr_model_desc),
        sheetTitle = stringResource(R.string.setting_model_page_ocr_separate_api_title),
        sheetDescription = stringResource(R.string.setting_model_page_ocr_separate_api_desc),
        providerName = "OCR OpenAI API",
        enabled = config.enabled,
        modelId = config.modelId,
        apiKey = config.apiKey,
        baseUrl = config.baseUrl,
        chatCompletionsPath = config.chatCompletionsPath,
        useResponseApi = config.useResponseApi,
        fallbackModelId = settings.ocrModelId,
        fallbackProviders = settings.providers,
        onEnabledChange = { enabled ->
            vm.updateSettings(settings.copy(ocrOpenAIConfig = config.copy(enabled = enabled)))
        },
        onConfigUpdate = { enabled, modelId, apiKey, baseUrl, path, useResponseApi ->
            vm.updateSettings(
                settings.copy(
                    ocrOpenAIConfig = config.copy(
                        enabled = enabled,
                        modelId = modelId,
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        chatCompletionsPath = path,
                        useResponseApi = useResponseApi,
                    )
                )
            )
        },
        onFallbackModelSelect = { model ->
            vm.updateSettings(settings.copy(ocrModelId = model.id))
        },
    )
}

@Composable
private fun CompressModelSettingItem(settings: Settings, vm: SettingVM) {
    val config = settings.compressOpenAIConfig
    SeparateApiModelSettingItem(
        title = stringResource(R.string.setting_model_page_compress_model),
        description = stringResource(R.string.setting_model_page_compress_model_desc),
        sheetTitle = stringResource(R.string.setting_model_page_compress_separate_api_title),
        sheetDescription = stringResource(R.string.setting_model_page_compress_separate_api_desc),
        providerName = "Compression OpenAI API",
        enabled = config.enabled,
        modelId = config.modelId,
        apiKey = config.apiKey,
        baseUrl = config.baseUrl,
        chatCompletionsPath = config.chatCompletionsPath,
        useResponseApi = config.useResponseApi,
        fallbackModelId = settings.compressModelId,
        fallbackProviders = settings.providers,
        onEnabledChange = { enabled ->
            vm.updateSettings(settings.copy(compressOpenAIConfig = config.copy(enabled = enabled)))
        },
        onConfigUpdate = { enabled, modelId, apiKey, baseUrl, path, useResponseApi ->
            vm.updateSettings(
                settings.copy(
                    compressOpenAIConfig = config.copy(
                        enabled = enabled,
                        modelId = modelId,
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        chatCompletionsPath = path,
                        useResponseApi = useResponseApi,
                    )
                )
            )
        },
        onFallbackModelSelect = { model ->
            vm.updateSettings(settings.copy(compressModelId = model.id))
        },
    )
}

@Composable
private fun SeparateApiConfigForm(
    title: String,
    description: String,
    enabled: Boolean,
    apiKey: String,
    baseUrl: String,
    chatCompletionsPath: String,
    useResponseApi: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onChatCompletionsPathChange: (String) -> Unit,
    onUseResponseApiChange: (Boolean) -> Unit,
) {
    FormItem(
        label = { Text(title) },
        description = { Text(description) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.setting_model_page_separate_api_enable))
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }

        if (enabled) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    label = { Text(stringResource(R.string.setting_model_page_separate_api_key)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = { Text(stringResource(R.string.setting_model_page_separate_api_base_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                )
                OutlinedTextField(
                    value = chatCompletionsPath,
                    onValueChange = onChatCompletionsPathChange,
                    label = { Text(stringResource(R.string.setting_model_page_separate_api_path)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.setting_model_page_separate_api_use_response))
                    Switch(checked = useResponseApi, onCheckedChange = onUseResponseApiChange)
                }
            }
        }
    }
}
