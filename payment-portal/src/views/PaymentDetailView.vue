<script setup>
import { onMounted, ref, computed } from 'vue'
import { usePaymentsStore } from '@/stores/payments'
import { formatAmount, formatDateTime, availableActions } from '@/utils/format'
import StatusBadge from '@/components/StatusBadge.vue'
import AmountDialog from '@/components/AmountDialog.vue'

const props = defineProps({ id: { type: String, required: true } })

const store = usePaymentsStore()
const dialog = ref(null) // 'capture' | 'refund' | null
const feedback = ref(null)

onMounted(() => store.fetchOne(props.id))

const payment = computed(() => store.current)
const busy = computed(() => (payment.value ? store.isPending(payment.value.id) : false))
const actions = computed(() => (payment.value ? availableActions(payment.value) : []))

const capturable = computed(() =>
  payment.value ? payment.value.authorizedAmount - payment.value.capturedAmount : 0,
)
const refundable = computed(() =>
  payment.value ? payment.value.capturedAmount - payment.value.refundedAmount : 0,
)

async function onConfirm({ amount, currency, reason }) {
  const id = payment.value.id
  const result =
    dialog.value === 'capture'
      ? await store.capture(id, amount, currency)
      : await store.refund(id, amount, currency, reason)

  dialog.value = null
  feedback.value = result.ok
    ? { tone: 'success', text: 'Operation enregistree.' }
    : { tone: 'danger', text: result.message }
}

async function onCancel() {
  // Confirmation explicite : annuler libere le hold chez l'emetteur et l'operation
  // n'est pas reversible -- il faudrait une nouvelle autorisation, que le client
  // n'est peut-etre plus la pour donner.
  if (!window.confirm("Annuler cette autorisation ? Le hold sera libere et l'encaissement ne sera plus possible sans nouvelle autorisation.")) {
    return
  }
  const result = await store.cancel(payment.value.id, 'Annulation depuis le portail')
  feedback.value = result.ok
    ? { tone: 'success', text: 'Autorisation annulee.' }
    : { tone: 'danger', text: result.message }
}
</script>

<template>
  <section v-if="payment">
    <header class="detail-header">
      <div>
        <h1>{{ payment.reservationId }}</h1>
        <p class="mono muted">{{ payment.id }}</p>
      </div>
      <StatusBadge :status="payment.status" />
    </header>

    <p v-if="feedback" :class="['notice', `notice--${feedback.tone}`]" role="status">
      {{ feedback.text }}
    </p>
    <p v-if="store.error" class="error" role="alert">{{ store.error }}</p>

    <dl class="detail-grid">
      <div><dt>Carte</dt><dd class="mono">{{ payment.cardBrand }} {{ payment.card }}</dd></div>
      <div><dt>Autorise</dt><dd>{{ formatAmount(payment.authorizedAmount, payment.currency) }}</dd></div>
      <div><dt>Encaisse</dt><dd>{{ formatAmount(payment.capturedAmount, payment.currency) }}</dd></div>
      <div><dt>Rembourse</dt><dd>{{ formatAmount(payment.refundedAmount, payment.currency) }}</dd></div>
      <div><dt>Reste a encaisser</dt><dd>{{ formatAmount(capturable, payment.currency) }}</dd></div>
      <div><dt>Reste a rembourser</dt><dd>{{ formatAmount(refundable, payment.currency) }}</dd></div>
      <div><dt>Reference prestataire</dt><dd class="mono">{{ payment.pspReference ?? '—' }}</dd></div>
      <div><dt>Authentification 3DS</dt><dd>{{ payment.threeDsOutcome ?? '—' }}</dd></div>
      <!-- Une autorisation a une duree de vie : passee cette date, le hold tombe et il
           faut re-autoriser pour encaisser. L'afficher evite les mauvaises surprises
           au check-out. -->
      <div>
        <dt>Autorisation valable jusqu'au</dt>
        <dd>{{ formatDateTime(payment.authorizationExpiresAt) }}</dd>
      </div>
      <div><dt>Cree le</dt><dd>{{ formatDateTime(payment.createdAt) }}</dd></div>
      <div v-if="payment.failureCode">
        <dt>Motif du refus</dt>
        <dd>{{ payment.failureCode }} — {{ payment.failureReason }}</dd>
      </div>
    </dl>

    <div class="actions">
      <button
        v-if="actions.includes('capture')"
        class="primary"
        :disabled="busy"
        @click="dialog = 'capture'"
      >
        Encaisser
      </button>
      <button v-if="actions.includes('refund')" :disabled="busy" @click="dialog = 'refund'">
        Rembourser
      </button>
      <button v-if="actions.includes('cancel')" class="danger" :disabled="busy" @click="onCancel">
        Annuler l'autorisation
      </button>
      <p v-if="!actions.length" class="muted">
        Aucune operation possible dans cet etat.
      </p>
    </div>

    <AmountDialog
      :open="dialog === 'capture'"
      title="Encaisser le paiement"
      :max-amount="capturable"
      :currency="payment.currency"
      :busy="busy"
      @confirm="onConfirm"
      @cancel="dialog = null"
    />

    <AmountDialog
      :open="dialog === 'refund'"
      title="Rembourser le paiement"
      :max-amount="refundable"
      :currency="payment.currency"
      with-reason
      :busy="busy"
      @confirm="onConfirm"
      @cancel="dialog = null"
    />
  </section>

  <p v-else-if="store.loading">Chargement…</p>
  <p v-else class="error">Paiement introuvable.</p>
</template>
