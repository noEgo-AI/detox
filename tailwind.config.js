/** @type {import('tailwindcss').Config} */
export default {
  content: ['./src/**/*.{html,js,svelte,ts}'],
  theme: {
    extend: {
      colors: {
        safe: '#10b981',
        warning: '#f59e0b',
        locked: '#ef4444',
        'bg-dark': '#0f172a',
        'bg-card': '#1e293b',
      },
    },
  },
  plugins: [],
};
