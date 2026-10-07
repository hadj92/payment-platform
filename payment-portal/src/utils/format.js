/**
 * Formatage des montants.
 *
 * L'API renvoie des unites mineures (centimes) : c'est la seule representation sans
 * ambiguite pour transporter de l'argent. La conversion en decimal se fait ici, au
 * dernier moment, pour l'affichage uniquement -- jamais avant un calcul.
 */
export function formatAmount(minorUnits, currency = 'EUR', locale = 'fr-FR') {
  const fractionDigits = currency === 'JPY' ? 0 : 2
  const value = minorUnits / 10 ** fractionDigits
  return new Intl.NumberFormat(locale, {
    style: 'currency',
    currency,
    minimumFractionDigits: fractionDigits,
  }).format(value)
}

export function formatDateTime(iso, locale = 'fr-FR') {
  if (!iso) return '—'
  return new Intl.DateTimeFormat(locale, {
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(new Date(iso))
}

/** Libelles metier : un hotelier ne lit pas « PARTIALLY_CAPTURED ». */
export const STATUS_LABELS = {
  PENDING: 'En cours',
  AUTHORIZED: 'Autorise',
  PARTIALLY_CAPTURED: 'Partiellement encaisse',
  CAPTURED: 'Encaisse',
  PARTIALLY_REFUNDED: 'Partiellement rembourse',
  REFUNDED: 'Rembourse',
  CANCELLED: 'Annule',
  DECLINED: 'Refuse',
  FAILED: 'Echec technique',
  EXPIRED: 'Autorisation expiree',
}

export const STATUS_TONES = {
  PENDING: 'neutral',
  AUTHORIZED: 'info',
  PARTIALLY_CAPTURED: 'info',
  CAPTURED: 'success',
  PARTIALLY_REFUNDED: 'warning',
  REFUNDED: 'warning',
  CANCELLED: 'neutral',
  DECLINED: 'danger',
  FAILED: 'danger',
  EXPIRED: 'danger',
}

/**
 * Operations possibles selon l'etat.
 *
 * La machine a etats du backend est repliquee ici UNIQUEMENT pour griser les boutons.
 * Le front ne fait jamais autorite : le serveur revalide chaque transition et renvoie
 * 409 si elle est interdite. Une regle metier appliquee seulement cote client n'est
 * pas une regle, c'est une suggestion.
 */
export function availableActions(payment) {
  switch (payment.status) {
    case 'AUTHORIZED':
      return ['capture', 'cancel']
    case 'PARTIALLY_CAPTURED':
      return ['capture', 'refund']
    case 'CAPTURED':
    case 'PARTIALLY_REFUNDED':
      return ['refund']
    default:
      return []
  }
}
