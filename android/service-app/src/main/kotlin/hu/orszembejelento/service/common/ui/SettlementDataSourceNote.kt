package hu.orszembejelento.service.common.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import hu.orszembejelento.service.R

/**
 * The CC BY 4.0 attribution for the KSH settlement data, as one line of small text that links to
 * the KSH address named in the licence's own required attribution string
 * ("Forrás: KSH — https://www.ksh.hu"; see `reference-data/LICENSES/KSH-helysegnevtar.md`).
 *
 * The wording is the owner-approved one and is unchanged; the only visual difference from plain
 * text is an underline that marks it as a link. Tapping it opens the address in the browser.
 */
@Composable
fun SettlementDataSourceNote(color: Color, modifier: Modifier = Modifier) {
    val text = stringResource(R.string.settlement_data_source)
    val url = stringResource(R.string.settlement_data_source_url)
    Text(
        text = buildAnnotatedString {
            withLink(LinkAnnotation.Url(url, TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline)))) {
                append(text)
            }
        },
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = modifier,
    )
}
