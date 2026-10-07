<script setup>
import { ref, computed, watch } from 'vue'
import { formatAmount } from '@/utils/format'

const props = defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, required: true },
  /** Plafond en unites mineures : montant capturable ou remboursable. */
  maxAmount: { type: Number, required: true },
  currency: { type: String, default: 'EUR' },
  withReason: { type: Boolean, default: false },
  busy: { type: Boolean, default: false },
})

const emit = defineEmits(['confirm', 'cancel'])

// Saisie en unites majeures (euros) : on ne demande pas a un hotelier de compter en
// centimes. La conversion se fait a la validation.
const amountInput = ref('')
const reason = ref('')

watch(
  () => props.open,
  (open) => {
    if (open) {
      amountInput.value = (props.maxAmount / 100).toFixed(2)
      reason.value = ''
    }
  },
)

const minorUnits = computed(() => Math.round(Number(amountInput.value.replace(',', '.')) * 100))

const validationError = computed(() => {
  if (amountInput.value === '') return 'Montant requis'
  if (Number.isNaN(minorUnits.value)) return 'Montant invalide'
  if (minorUnits.value <= 0) return 'Le montant doit etre superieur a zero'
  if (minorUnits.value > props.maxAmount) {
    return `Maximum ${formatAmount(props.maxAmount, props.currency)}`
  }
  return null
})

function confirm() {
  if (validationError.value) return
  emit('confirm', { amount: minorUnits.value, currency: props.currency, reason: reason.value })
}
</script>

<template>
  <div v-if="open" class="dialog-backdrop" @click.self="emit('cancel')">
    <div class="dialog" role="dialog" aria-modal="true" :aria-label="title">
      <h2>{{ title }}</h2>

      <label>
        Montant
        <input
          v-model="amountInput"
          type="text"
          inputmode="decimal"
          :disabled="busy"
          @keyup.enter="confirm"
        />
      </label>
      <p class="hint">Maximum : {{ formatAmount(maxAmount, currency) }}</p>

      <label v-if="withReason">
        Motif
        <input v-model="reason" type="text" :disabled="busy" maxlength="255" />
      </label>

      <p v-if="validationError" class="error">{{ validationError }}</p>

      <div class="dialog-actions">
        <button type="button" :disabled="busy" @click="emit('cancel')">Annuler</button>
        <!-- Le bouton est desactive pendant l'operation : premiere barriere contre le
             double clic. La seconde est la cle d'idempotence cote serveur. -->
        <button
          type="button"
          class="primary"
          :disabled="busy || !!validationError"
          @click="confirm"
        >
          {{ busy ? 'Traitement…' : 'Confirmer' }}
        </button>
      </div>
    </div>
  </div>
</template>
