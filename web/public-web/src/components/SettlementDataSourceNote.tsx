import type { CSSProperties } from 'react'
import { strings } from '../strings'

/**
 * The CC BY 4.0 attribution for the KSH settlement data, as one line of small text that links to
 * the KSH address named in the licence's own required attribution string
 * ("Forrás: KSH — https://www.ksh.hu"; see reference-data/LICENSES/KSH-helysegnevtar.md).
 *
 * The wording is the owner-approved one and is unchanged; the only visual difference from plain
 * text is an underline that marks it as a link. It opens in a new tab so the person keeps their
 * place in the app, and `noopener noreferrer` stops the destination getting a handle on it.
 */
export function SettlementDataSourceNote({ style }: { readonly style?: CSSProperties }) {
  return (
    <p className="muted" style={style}>
      <a href={strings.settlementDataSourceUrl} target="_blank" rel="noopener noreferrer">
        {strings.settlementDataSource}
      </a>
    </p>
  )
}
