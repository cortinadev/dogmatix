package com.cortinadev.dogmatix.ui.screens.contact

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource

private data class CreditLink(val iconRes: Int, val labelRes: Int, val url: String, val display: String)

private val forkLinks = listOf(
    CreditLink(R.drawable.ic_github, R.string.credits_link_github, "https://github.com/cortinadev", "github.com/cortinadev"),
    CreditLink(R.drawable.ic_linkedin, R.string.credits_link_linkedin, "https://www.linkedin.com/in/rafa-cortina", "linkedin.com/in/rafa-cortina"),
    CreditLink(R.drawable.ic_web, R.string.credits_link_web, "https://cortina.dev", "cortina.dev"),
)

private val originalLinks = listOf(
    CreditLink(R.drawable.ic_github, R.string.credits_link_github, "https://github.com/santiifm", "github.com/santiifm"),
    CreditLink(R.drawable.ic_linkedin, R.string.credits_link_linkedin, "https://www.linkedin.com/in/santiifm", "linkedin.com/in/santiifm"),
)

@Composable
fun ContactScreen(navController: NavController) {
    val isLandscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.credits_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            if (isLandscape) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                    ForkCard(Modifier.weight(1f))
                    OriginalCard(Modifier.weight(1f))
                }
            } else {
                ForkCard(Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                OriginalCard(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ForkCard(modifier: Modifier = Modifier) = CreditCard(
    name = stringResource(R.string.credits_fork_name),
    role = stringResource(R.string.credits_fork_role),
    links = forkLinks,
    modifier = modifier
)

@Composable
private fun OriginalCard(modifier: Modifier = Modifier) = CreditCard(
    name = stringResource(R.string.credits_original_name),
    role = stringResource(R.string.credits_original_role),
    note = stringResource(R.string.credits_original_note),
    links = originalLinks,
    modifier = modifier
)

@Composable
private fun CreditCard(
    name: String,
    role: String,
    links: List<CreditLink>,
    modifier: Modifier = Modifier,
    note: String? = null
) {
    Column(
        modifier = modifier
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(20.dp)
    ) {
        Text(text = name, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
        Text(
            text = role,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        links.forEach { LinkRow(it) }
    }
}

@Composable
private fun LinkRow(link: CreditLink) {
    val uriHandler = LocalUriHandler.current
    val source = rememberFocusSource()
    val label = stringResource(link.labelRes)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .focusRing(source)
            .clickable(interactionSource = source, indication = null) { uriHandler.openUri(link.url) }
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Icon(
            painter = painterResource(link.iconRes),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = link.display,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}
