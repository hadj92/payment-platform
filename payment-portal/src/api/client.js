/**
 * Client de l'API de paiement.
 *
 * Trois responsabilites qu'on ne veut pas voir dispersees dans les composants :
 * la generation des cles d'idempotence, la lecture du format ProblemDetail, et la
 * politique de retry.
 */

/** Erreur portant le code et le caractere rejouable renvoyes par l'API. */
export class ApiError extends Error {
  constructor({ status, code, detail, retryable, issuerCode, errors }) {
    super(detail || `Erreur HTTP ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.retryable = retryable ?? false
    this.issuerCode = issuerCode
    this.errors = errors
  }
}

/**
 * Genere une cle d'idempotence.
 *
 * Point important cote front : la cle est generee UNE FOIS par intention de
 * l'utilisateur, pas une fois par appel HTTP. Si on en regenerait une a chaque
 * tentative, le retry creerait un second paiement -- c'est-a-dire exactement ce que
 * l'idempotence est censee empecher.
 */
export function newIdempotencyKey() {
  return crypto.randomUUID()
}

async function parseError(response) {
  let body = {}
  try {
    body = await response.json()
  } catch {
    // Reponse non JSON (502 d'un proxy, page d'erreur) : on garde juste le statut.
  }
  return new ApiError({
    status: response.status,
    code: body.code,
    detail: body.detail,
    retryable: body.retryable,
    issuerCode: body.issuerCode,
    errors: body.errors,
  })
}

/**
 * Appel HTTP avec retry sur les seules erreurs rejouables.
 *
 * La regle a retenir : on retente avec la MEME cle d'idempotence. C'est tout
 * l'interet du mecanisme -- si la premiere tentative avait en fait abouti, le serveur
 * rejoue sa reponse au lieu de debiter une seconde fois.
 *
 * On ne retente jamais un 402 (refus de l'emetteur) : la decision est definitive, et
 * marteler le PSP de tentatives deja refusees degrade le taux d'autorisation et se
 * facture.
 */
async function request(path, { method = 'GET', body, idempotencyKey, maxAttempts = 3 } = {}) {
  const headers = { Accept: 'application/json' }
  if (body) headers['Content-Type'] = 'application/json'
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey

  let lastError
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    let response
    try {
      response = await fetch(path, {
        method,
        headers,
        body: body ? JSON.stringify(body) : undefined,
      })
    } catch (networkError) {
      // Coupure reseau : l'issue est indeterminee, la requete a peut-etre abouti.
      // Retenter avec la meme cle est sans danger, c'est precisement le cas d'usage.
      lastError = new ApiError({ status: 0, code: 'network_error', detail: networkError.message, retryable: true })
      if (attempt < maxAttempts) {
        await backoff(attempt)
        continue
      }
      throw lastError
    }

    if (response.ok) {
      return response.status === 204 ? null : response.json()
    }

    const error = await parseError(response)
    if (!error.retryable || attempt === maxAttempts) {
      throw error
    }
    lastError = error
    await backoff(attempt)
  }
  throw lastError
}

/** Backoff exponentiel avec jitter : sans jitter, tous les clients retentent en choeur. */
function backoff(attempt) {
  const base = 300 * 2 ** (attempt - 1)
  const jitter = Math.random() * base * 0.5
  return new Promise((resolve) => setTimeout(resolve, base + jitter))
}

export const paymentsApi = {
  list({ hotelId, status, page = 0, size = 20 }) {
    const params = new URLSearchParams({ hotelId, page: String(page), size: String(size) })
    if (status) params.set('status', status)
    return request(`/v1/payments?${params}`)
  },

  get(paymentId) {
    return request(`/v1/payments/${paymentId}`)
  },

  byReservation(reservationId) {
    return request(`/v1/payments/by-reservation/${encodeURIComponent(reservationId)}`)
  },

  authorize(payload, idempotencyKey) {
    return request('/v1/payments', { method: 'POST', body: payload, idempotencyKey })
  },

  capture(paymentId, { amount, currency }, idempotencyKey) {
    return request(`/v1/payments/${paymentId}/capture`, {
      method: 'POST',
      body: { amount, currency },
      idempotencyKey,
    })
  },

  refund(paymentId, { amount, currency, reason }, idempotencyKey) {
    return request(`/v1/payments/${paymentId}/refund`, {
      method: 'POST',
      body: { amount, currency, reason },
      idempotencyKey,
    })
  },

  cancel(paymentId, reason, idempotencyKey) {
    return request(`/v1/payments/${paymentId}/cancel`, {
      method: 'POST',
      body: { reason },
      idempotencyKey,
    })
  },
}
