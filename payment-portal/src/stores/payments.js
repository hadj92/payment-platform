import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { paymentsApi, newIdempotencyKey, ApiError } from '@/api/client'

/**
 * Store des paiements (Pinia, syntaxe setup).
 *
 * Pourquoi la syntaxe setup plutot que les options : c'est du code Composition API
 * ordinaire, donc typable, testable sans monter de composant, et sans la magie de
 * `this`. C'est aussi ce qui permet d'extraire une logique en composable si elle doit
 * etre reutilisee ailleurs.
 */
export const usePaymentsStore = defineStore('payments', () => {
  const items = ref([])
  const current = ref(null)
  const loading = ref(false)
  const error = ref(null)
  const page = ref(0)
  const totalPages = ref(0)
  const filters = ref({ hotelId: 'HOTEL-PAR-001', status: null })

  /**
   * Operations en cours, par paiement.
   *
   * Ce n'est pas qu'un detail d'UX : sans ce verrou, un double clic sur "Capturer"
   * envoie deux requetes. La cle d'idempotence protege le serveur, mais l'interface
   * doit aussi empecher le geste -- la defense se fait aux deux bouts.
   */
  const pendingOperations = ref(new Set())

  const isPending = computed(() => (paymentId) => pendingOperations.value.has(paymentId))

  const totals = computed(() => {
    const captured = items.value.reduce((sum, p) => sum + p.capturedAmount, 0)
    const refunded = items.value.reduce((sum, p) => sum + p.refundedAmount, 0)
    return { captured, refunded, net: captured - refunded }
  })

  async function fetchPage(nextPage = 0) {
    loading.value = true
    error.value = null
    try {
      const data = await paymentsApi.list({ ...filters.value, page: nextPage })
      items.value = data.content
      page.value = data.number
      totalPages.value = data.totalPages
    } catch (e) {
      error.value = messageFor(e)
    } finally {
      loading.value = false
    }
  }

  async function fetchOne(paymentId) {
    loading.value = true
    error.value = null
    try {
      current.value = await paymentsApi.get(paymentId)
    } catch (e) {
      error.value = messageFor(e)
    } finally {
      loading.value = false
    }
  }

  /**
   * Execute une operation mutante sur un paiement.
   *
   * La cle d'idempotence est generee ici, une seule fois, et transmise au client qui
   * la reutilise pour chacune de ses tentatives internes.
   */
  async function runOperation(paymentId, operation) {
    if (pendingOperations.value.has(paymentId)) {
      return { ok: false, message: 'Une operation est deja en cours sur ce paiement.' }
    }
    pendingOperations.value = new Set(pendingOperations.value).add(paymentId)
    error.value = null
    try {
      const key = newIdempotencyKey()
      const updated = await operation(key)
      replaceInList(updated)
      if (current.value?.id === updated.id) current.value = updated
      return { ok: true, payment: updated }
    } catch (e) {
      const message = messageFor(e)
      error.value = message
      return { ok: false, message }
    } finally {
      const next = new Set(pendingOperations.value)
      next.delete(paymentId)
      pendingOperations.value = next
    }
  }

  const capture = (paymentId, amount, currency) =>
    runOperation(paymentId, (key) => paymentsApi.capture(paymentId, { amount, currency }, key))

  const refund = (paymentId, amount, currency, reason) =>
    runOperation(paymentId, (key) => paymentsApi.refund(paymentId, { amount, currency, reason }, key))

  const cancel = (paymentId, reason) =>
    runOperation(paymentId, (key) => paymentsApi.cancel(paymentId, reason, key))

  function replaceInList(updated) {
    const index = items.value.findIndex((p) => p.id === updated.id)
    if (index !== -1) items.value[index] = updated
  }

  /**
   * Traduction des erreurs d'API en messages pour un hotelier.
   *
   * L'enjeu est reel : la personne a la reception n'a pas a interpreter un code HTTP.
   * Elle doit savoir ce qui s'est passe et quoi faire -- demander un autre moyen de
   * paiement, ou simplement reessayer.
   */
  function messageFor(e) {
    if (!(e instanceof ApiError)) return 'Une erreur inattendue est survenue.'
    switch (e.code) {
      case 'payment_declined':
        return `Paiement refuse par la banque du client${e.issuerCode ? ` (code ${e.issuerCode})` : ''}. Demandez un autre moyen de paiement.`
      case 'amount_exceeds_available':
        return 'Le montant demande depasse le montant disponible sur ce paiement.'
      case 'invalid_state_transition':
        return "Cette operation n'est pas possible dans l'etat actuel du paiement."
      case 'psp_unavailable':
        return 'Le service de paiement est momentanement indisponible. Reessayez dans quelques instants.'
      case 'request_in_progress':
      case 'concurrent_modification':
        return 'Une autre operation est en cours sur ce paiement. Patientez puis rafraichissez.'
      case 'network_error':
        return 'Connexion interrompue. Verifiez l\'etat du paiement avant de reessayer.'
      default:
        return e.message
    }
  }

  return {
    items, current, loading, error, page, totalPages, filters,
    isPending, totals,
    fetchPage, fetchOne, capture, refund, cancel,
  }
})
