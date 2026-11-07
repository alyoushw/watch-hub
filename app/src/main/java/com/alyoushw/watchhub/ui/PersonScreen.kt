package com.alyoushw.watchhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.alyoushw.watchhub.AppContainer
import com.alyoushw.watchhub.data.ApiException
import com.alyoushw.watchhub.data.MediaType
import com.alyoushw.watchhub.data.PersonDetails
import com.alyoushw.watchhub.data.SearchResult
import com.alyoushw.watchhub.data.TmdbApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Period

class PersonViewModel(private val container: AppContainer, private val id: Int) : ViewModel() {
    var person by mutableStateOf<PersonDetails?>(null)
        private set
    var englishBio by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            error = null
            try {
                coroutineScope {
                    val ar = async { container.api.person(id) }
                    val en = async { runCatching { container.api.personEnglish(id) }.getOrNull() }
                    person = ar.await()
                    englishBio = en.await()?.biography?.takeIf { it.isNotBlank() }
                }
            } catch (e: ApiException) {
                error = e.message
            }
        }
    }
}

private enum class WorksFilter(val label: String) { ALL("الكل"), TV("مسلسلات"), MOVIE("أفلام") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonScreen(id: Int, onBack: () -> Unit, onOpen: (MediaType, Int) -> Unit) {
    val container = appContainer()
    val vm: PersonViewModel = viewModel { PersonViewModel(container, id) }
    val person = vm.person
    var filter by remember { mutableStateOf(WorksFilter.ALL) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(person?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(modifier = Modifier.focusFrame(), onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                person != null -> {
                    // Every film/series they appear in, most popular first, without duplicates.
                    val works = person.credits?.cast.orEmpty()
                        .filter { it.posterPath != null && it.type != null }
                        .filter {
                            when (filter) {
                                WorksFilter.ALL -> true
                                WorksFilter.TV -> it.type == MediaType.TV
                                WorksFilter.MOVIE -> it.type == MediaType.MOVIE
                            }
                        }
                        .distinctBy { "${it.mediaType}:${it.id}" }
                        .sortedByDescending { it.popularity ?: 0.0 }

                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(112.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) { PersonHeader(person, vm.englishBio) }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SectionTitle("أعماله (${works.size})")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    WorksFilter.entries.forEach { f ->
                                        FilterChip(
                                            modifier = Modifier.focusFrame(),
                                            selected = filter == f,
                                            onClick = { filter = f },
                                            label = { Text(f.label) },
                                        )
                                    }
                                }
                            }
                        }
                        items(works, key = { "${it.mediaType}:${it.id}" }) { work ->
                            Column {
                                PosterCard(work, width = 108.dp) { work.type?.let { onOpen(it, work.id) } }
                                work.character?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        "بدور: $it",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                vm.error != null -> MessageBox(vm.error.orEmpty(), actionLabel = "حاول مرة ثانية", onAction = vm::load)
                else -> CenteredLoading()
            }
        }
    }
}

@Composable
private fun PersonHeader(person: PersonDetails, englishBio: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 120.dp, height = 170.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (person.profilePath != null) {
                    AsyncImage(
                        model = TmdbApi.image(person.profilePath, "w342"),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(person.name.take(1), style = MaterialTheme.typography.headlineLarge)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(person.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                // An Arabic spelling of the name, if TMDB has one.
                person.alsoKnownAs.firstOrNull { n -> n.any { it in '؀'..'ۿ' } && n != person.name }?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                departmentLabel(person.department)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                person.birthday?.let { birthday ->
                    val age = runCatching {
                        val born = LocalDate.parse(birthday)
                        val end = person.deathday?.let { LocalDate.parse(it) } ?: LocalDate.now()
                        Period.between(born, end).years
                    }.getOrNull()
                    Text(
                        "مواليد $birthday" + (age?.let { " (العمر $it)" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                person.placeOfBirth?.takeIf { it.isNotBlank() }?.let {
                    Text("من $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val arabicBio = person.biography?.trim().orEmpty()
        val bio = arabicBio.ifBlank { englishBio.orEmpty() }
        if (bio.isNotBlank()) {
            SectionTitle("السيرة")
            TranslatedText(bio, isEnglish = arabicBio.isBlank(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun departmentLabel(department: String?): String? = when (department) {
    null, "" -> null
    "Acting" -> "ممثل"
    "Directing" -> "مخرج"
    "Writing" -> "كاتب"
    "Production" -> "منتج"
    else -> department
}
