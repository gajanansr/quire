package app.quire.android.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.quire.android.data.AppSettingsEntity
import app.quire.android.ui.common.PrimaryButton
import app.quire.android.ui.theme.Quire

/** One page of the opening guide: a headline and a couple of short lines. */
data class GuidePage(val headline: String, val lines: List<String>)

/**
 * The guide shown once, on the first launch after an install.
 *
 * Three pages, and then the goal picker that was already here. Three rather than
 * five because this app's voice is restraint and a tour is not a product: a reader
 * who wanted a carousel would not have installed an offline reading app. Every page
 * is skippable from the first, and the whole thing is shown once and never again.
 *
 * The content lives here as data rather than inside the composable so that the rules
 * about it can be asserted — that it stays short, that no page promises something
 * Quire does not do, that nothing in it is a number nobody can check. There is no
 * Compose UI test dependency in this project and this file does not add one.
 */
object OnboardingGuide {

    /** More than this stops being an introduction and starts being an advert. */
    const val MAX_PAGES = 3

    val pages: List<GuidePage> = listOf(
        GuidePage(
            "A quiet place to read.",
            listOf(
                "Quire opens your own books and then gets out of the way.",
                // "nothing to subscribe to" was the first draft and the test caught
                // it, which turned out to be the right answer for a second reason:
                // denying a feature in its own marketing vocabulary still puts the
                // feature in the reader's head. Plain words say more and promise
                // less.
                "There is no feed, no account, and nothing to join.",
            ),
        ),
        GuidePage(
            "Your books, and they stay yours.",
            listOf(
                "Add an EPUB, PDF or TXT from your phone's own files. Quire keeps " +
                    "its own copy, so you can move or delete the original afterwards.",
                // Said plainly and early, because it is the reason to trust the rest
                // of the app and it is checkable: Quire's manifest declares no
                // INTERNET permission, which NoNetworkPermissionTest holds it to.
                "Nothing leaves this device. Quire cannot reach the internet at all — " +
                    "not with your books, not with what you read.",
            ),
        ),
        GuidePage(
            "Turn a page, and settle in.",
            listOf(
                "Tap the right of the page to go on, the left to go back, or swipe.",
                "Tap the middle for the toolbar, where the type size, the typefaces " +
                    "and the themes live.",
            ),
        ),
    )

    /** True when [index] is the last page, so the button says Continue rather than Next. */
    fun isLast(index: Int): Boolean = index >= pages.lastIndex

    /**
     * The page after [index], or null when the guide is finished.
     *
     * Null rather than a clamped index: "there is no next page" and "stay where you
     * are" are different answers, and returning the last index for both would leave
     * a reader on a final page whose button did nothing.
     */
    fun next(index: Int): Int? = if (isLast(index)) null else index + 1

    /**
     * Whether the guide is the screen in front of the reader.
     *
     * The null check is the whole point and is not defensive coding. `AppSettingsEntity()`
     * defaults to an un-onboarded reader, so treating "the row has not arrived yet"
     * as "this is a new install" rendered the welcome screen for a frame on every
     * cold start — someone who had used Quire for months was greeted with it each
     * time they opened the app. The honest state before the database answers is
     * "not known", and the screen for that is a blank page, not a guess.
     *
     * [finishedThisSession] covers the gap between finishing the guide and the write
     * landing: without it, the last tap would leave the guide on screen until the
     * flow re-emitted.
     */
    fun guideVisible(settings: AppSettingsEntity?, finishedThisSession: Boolean): Boolean =
        settings != null && !settings.guideSeen && !finishedThisSession

    /**
     * Whether the goal picker follows.
     *
     * Gated on `onboarded`, which only the goal picker sets — so a reader who read
     * the guide and closed Quire before choosing a goal comes back to the goal
     * picker rather than to the guide again. That split is why `guideSeen` exists as
     * its own column.
     */
    fun goalVisible(settings: AppSettingsEntity?, finishedThisSession: Boolean): Boolean =
        settings != null &&
            !settings.onboarded &&
            !guideVisible(settings, finishedThisSession)
}

/**
 * The guide itself.
 *
 * One page at a time with a dot for each, Skip always in reach. Deliberately not a
 * horizontal pager: a pager invites swiping through without reading, and three
 * pages do not need the gesture.
 */
@Composable
fun OnboardingGuideScreen(
    index: Int,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Quire.colors
    val page = OnboardingGuide.pages[index.coerceIn(0, OnboardingGuide.pages.lastIndex)]

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .padding(horizontal = 30.dp)
            .padding(top = 60.dp, bottom = 40.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            // On every page, including the last. Skipping is something a reader
            // should be able to do the moment they decide to, not once they have
            // read their way to the end of the thing they wanted to skip.
            Text(
                "Skip",
                color = colors.muted,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onSkip)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        Spacer(Modifier.height(30.dp))
        Text(
            page.headline,
            color = colors.ink,
            style = MaterialTheme.typography.displayLarge,
        )
        Spacer(Modifier.height(22.dp))
        page.lines.forEach { line ->
            Text(
                line,
                color = colors.muted,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(14.dp))
        }

        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth().padding(bottom = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OnboardingGuide.pages.indices.forEach { dot ->
                Box(
                    Modifier
                        .size(if (dot == index) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(if (dot == index) colors.accent else colors.border),
                )
            }
        }
        PrimaryButton(if (OnboardingGuide.isLast(index)) "Continue" else "Next", onNext)
        Spacer(Modifier.navigationBarsPadding())
    }
}
