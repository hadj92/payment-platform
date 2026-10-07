<script setup>
import { RouterView, RouterLink } from 'vue-router'
</script>

<template>
  <div class="app">
    <header class="app-header">
      <RouterLink to="/" class="brand">Portail paiements</RouterLink>
      <span class="hotel">HOTEL-PAR-001</span>
    </header>

    <main class="app-main">
      <RouterView />
    </main>

    <footer class="app-footer">
      Projet de demonstration — prestataire de paiement simule, aucune donnee carte reelle.
    </footer>
  </div>
</template>
