<script setup>
import { onMounted, computed } from 'vue'
import { RouterLink } from 'vue-router'
import { usePaymentsStore } from '@/stores/payments'
import { formatAmount, formatDateTime, STATUS_LABELS } from '@/utils/format'
import StatusBadge from '@/components/StatusBadge.vue'

const store = usePaymentsStore()

onMounted(() => store.fetchPage(0))

const statusOptions = computed(() => Object.entries(STATUS_LABELS))

function applyFilter(status) {
  store.filters.status = status || null
  store.fetchPage(0)
}
</script>

<template>
  <section>
    <div class="toolbar">
      <h1>Paiements</h1>

      <label>
        Statut
        <select @change="applyFilter($event.target.value)">
          <option value="">Tous</option>
          <option v-for="[value, label] in statusOptions" :key="value" :value="value">
            {{ label }}
          </option>
        </select>
      </label>
    </div>

    <!-- Encaissement net = capture - remboursements. C'est le chiffre que l'hotelier
         rapproche de son releve bancaire, pas la somme des autorisations : une
         autorisation non capturee ne represente aucun argent recu. -->
    <div class="totals">
      <div><span>Encaisse</span><strong>{{ formatAmount(store.totals.captured) }}</strong></div>
      <div><span>Rembourse</span><strong>{{ formatAmount(store.totals.refunded) }}</strong></div>
      <div><span>Net</span><strong>{{ formatAmount(store.totals.net) }}</strong></div>
    </div>

    <p v-if="store.error" class="error" role="alert">{{ store.error }}</p>
    <p v-if="store.loading">Chargement…</p>

    <table v-else-if="store.items.length" class="payments">
      <thead>
        <tr>
          <th>Reservation</th>
          <th>Carte</th>
          <th>Statut</th>
          <th class="num">Autorise</th>
          <th class="num">Encaisse</th>
          <th>Date</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="payment in store.items" :key="payment.id">
          <td>{{ payment.reservationId }}</td>
          <!-- Masque PCI DSS : BIN + 4 derniers chiffres. L'API ne renvoie rien de plus,
               et il n'y a donc rien de sensible a proteger dans ce tableau. -->
          <td class="mono">{{ payment.cardBrand }} {{ payment.card }}</td>
          <td><StatusBadge :status="payment.status" /></td>
          <td class="num">{{ formatAmount(payment.authorizedAmount, payment.currency) }}</td>
          <td class="num">{{ formatAmount(payment.capturedAmount, payment.currency) }}</td>
          <td>{{ formatDateTime(payment.createdAt) }}</td>
          <td>
            <RouterLink :to="{ name: 'payment-detail', params: { id: payment.id } }">
              Detail
            </RouterLink>
          </td>
        </tr>
      </tbody>
    </table>

    <p v-else>Aucun paiement pour ce filtre.</p>

    <nav v-if="store.totalPages > 1" class="pagination">
      <button :disabled="store.page === 0" @click="store.fetchPage(store.page - 1)">
        Precedent
      </button>
      <span>Page {{ store.page + 1 }} / {{ store.totalPages }}</span>
      <button
        :disabled="store.page >= store.totalPages - 1"
        @click="store.fetchPage(store.page + 1)"
      >
        Suivant
      </button>
    </nav>
  </section>
</template>
