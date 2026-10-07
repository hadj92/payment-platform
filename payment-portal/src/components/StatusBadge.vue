<script setup>
import { computed } from 'vue'
import { STATUS_LABELS, STATUS_TONES } from '@/utils/format'

const props = defineProps({
  status: { type: String, required: true },
})

const label = computed(() => STATUS_LABELS[props.status] ?? props.status)
const tone = computed(() => STATUS_TONES[props.status] ?? 'neutral')
</script>

<template>
  <!-- Le statut n'est pas signale par la couleur seule : le libelle textuel porte
       l'information, pour rester lisible par un lecteur d'ecran et en cas de daltonisme. -->
  <span class="badge" :class="`badge--${tone}`">{{ label }}</span>
</template>
