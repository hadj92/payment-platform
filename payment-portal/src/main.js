import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import App from '@/App.vue'
import PaymentListView from '@/views/PaymentListView.vue'
import PaymentDetailView from '@/views/PaymentDetailView.vue'
import '@/style.css'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'payments', component: PaymentListView },
    { path: '/payments/:id', name: 'payment-detail', component: PaymentDetailView, props: true },
  ],
})

createApp(App).use(createPinia()).use(router).mount('#app')
