import type { Config } from 'tailwindcss';

// Two-colour system: ink (deep navy) and accent (blue).
// Every other shade in the UI is one of these at reduced opacity (e.g. bg-ink/5, text-ink/60).
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        ink: '#0B1F3A',
        accent: { DEFAULT: '#2563EB', strong: '#1D4FD1' },
        canvas: '#F7F8FA',
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', '-apple-system', 'Segoe UI', 'Roboto', 'sans-serif'],
        mono: ['"JetBrains Mono"', 'ui-monospace', 'SFMono-Regular', 'Consolas', 'monospace'],
      },
      boxShadow: {
        card: '0 1px 2px rgba(11,31,58,0.06), 0 1px 1px rgba(11,31,58,0.03)',
        pop: '0 12px 40px rgba(11,31,58,0.18)',
      },
      keyframes: {
        'fade-in': { from: { opacity: '0', transform: 'translateY(4px)' }, to: { opacity: '1', transform: 'none' } },
        'slide-in': { from: { transform: 'translateX(100%)' }, to: { transform: 'none' } },
        pulse: { '0%,100%': { opacity: '1' }, '50%': { opacity: '.45' } },
      },
      animation: {
        'fade-in': 'fade-in .18s ease-out',
        'slide-in': 'slide-in .22s ease-out',
      },
    },
  },
  plugins: [],
} satisfies Config;
