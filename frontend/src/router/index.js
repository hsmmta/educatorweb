import { createRouter, createWebHistory } from 'vue-router'
import MainLayout from '../views/MainLayout.vue'

const routes = [
  { path: '/', redirect: '/login' },
  { path: '/login', component: () => import('../views/Login.vue') },
  { path: '/register', component: () => import('../views/Register.vue') },
  {
    path: '/',
    component: MainLayout,
    meta: { requiresAuth: true },
    children: [
      { path: 'home',      component: () => import('../views/Home.vue') },
      { path: 'chat',      component: () => import('../views/Chat.vue') },
      { path: 'thinktank', component: () => import('../views/ThinkTank.vue') },
      { path: 'tutoring',  component: () => import('../views/Tutoring.vue') },
      { path: 'learning',  component: () => import('../views/Learning.vue') },
      { path: 'push',      component: () => import('../views/ResourcePush.vue') },
      { path: 'profile',   component: () => import('../views/Profile.vue') },
      { path: 'profile/edit', component: () => import('../views/EditProfile.vue') },
      { path: 'profile/chat', component: () => import('../views/ProfileChat.vue') }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')
  if (to.meta.requiresAuth && !token) {
    next('/login')
  } else {
    next()
  }
})

export default router
