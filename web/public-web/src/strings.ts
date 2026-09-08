/**
 * Centralized Hungarian UI copy (§52) - components reference these constants rather than
 * duplicating literal strings. Mirrors `android/public-app/.../res/values/strings.xml`
 * exactly where the same product copy applies to both clients.
 */
export const strings = {
  appName: 'Őrszem',

  navHome: 'Kezdőlap',
  navNewReport: 'Új bejelentés',
  navHistory: 'Előzmények',

  homeHeadline: 'Őrszem',
  homeBody: 'Vasúti környezetben tapasztalt eseményeket néhány lépésben, névtelenül lehet bejelenteni.',
  homeNewReportCta: 'Új bejelentés',
  homeRecentHistoryTitle: 'Legutóbbi bejelentések',
  homeNoHistory: 'Még nincs bejelentés ezen a böngészőben.',

  step1Title: '1. Alapadatok',
  fieldOccurredAt: 'Időpont',
  fieldTrainIdentifier: 'Vonatszám (nem kötelező)',
  fieldSettlement: 'Település',
  fieldSettlementHint: 'Település keresése',

  lineNoneVerified: 'Ehhez a településhez jelenleg nincs ellenőrzött vasútvonal-kapcsolat nyilvántartva. Ez nem jelenti azt, hogy nincs vasútvonal a településen.',
  lineAutoIdentified: (name: string) => `Azonosított vasútvonal: ${name}`,
  lineChooseTitle: 'Melyik vasútvonalról van szó?',
  lineChooseUnsure: 'Nem tudom / nem vagyok biztos benne',
  lineChooseUnsurePartial: 'Nem tudom / másik vonal',

  actionNext: 'Tovább',
  actionBack: 'Vissza',
  actionSubmit: 'Bejelentés küldése',
  actionRetry: 'Újrapróbálás',
  actionNewReport: 'Új bejelentés',
  actionViewHistory: 'Előzmények',
  actionHome: 'Kezdőlap',

  step2Title: '2. Esemény',
  categoryChooseTitle: 'Kategória',
  eventTypeChooseTitle: 'Esemény típusa',
  catalogLoadFailed: 'A kategórialista betöltése nem sikerült.',

  successTitle: 'Bejelentés elküldve',
  successReportId: (id: string) => `Bejelentés azonosítója: ${id}`,
  successEventType: (name: string) => `Esemény: ${name}`,
  successTrain: (train: string) => `Vonatszám: ${train}`,
  successSettlement: (name: string) => `Település: ${name}`,
  successStatus: (status: string) => `Állapot: ${status}`,

  historyTitle: 'Előzmények',
  historyEmpty: 'Még nincs bejelentés ezen a böngészőben.',
  historyUnconfirmed: 'Beküldés nincs megerősítve',
  historyAccessLost: 'A bejelentéshez való helyi hozzáférés elveszett.',
  historyConflict: 'A bejelentés állapota nem egyeztethető, kérjük indítson új bejelentést.',
  historyLastChecked: (when: string) => `Utoljára ellenőrizve: ${when}`,
  historyRefresh: 'Frissítés',
  historyStorageNotice:
    'Az előzmények csak ebben a böngészőben tárolódnak, és törlődhetnek a böngésző vagy az oldal adatainak törlésekor.',
  statusReceived: 'Beérkezett',
  statusProcessing: 'Feldolgozás alatt',
  statusClosed: 'Lezárva',

  errorValidation: 'A megadott adatok nem megfelelőek. Kérjük, ellenőrizze a mezőket.',
  errorReferenceUnavailable: 'A vonatkoztatási adatok jelenleg nem érhetők el. Kérjük, próbálja meg később.',
  errorNetwork: 'Hálózati hiba történt. A bejelentés nincs megerősítve, próbálja újra.',
  errorConflict: 'A bejelentés nem küldhető el emiatt az azonosító miatt. Kérjük, indítson új bejelentést.',
  errorLocalStorage: 'A böngésző nem tudta biztonságosan elmenteni a bejelentés helyi adatait, ezért a bejelentés nem lett elküldve.',
  errorAccessLost: 'A bejelentéshez való helyi hozzáférés nem érhető el ebben a böngészőben.',
  errorGeneric: 'Váratlan hiba történt.',

  locateUnavailableWeb: 'A helyzet meghatározása nem érhető el a webes alkalmazásban - kérjük, keresse meg a települést kézzel.',
} as const

export function publicStatusLabel(status: 'RECEIVED' | 'PROCESSING' | 'CLOSED'): string {
  switch (status) {
    case 'RECEIVED':
      return strings.statusReceived
    case 'PROCESSING':
      return strings.statusProcessing
    case 'CLOSED':
      return strings.statusClosed
  }
}
