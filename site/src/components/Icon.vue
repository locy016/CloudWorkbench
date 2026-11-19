<script setup>
defineProps({ name: { type: String, default: 'arrow' } })
const paths = {
  arrow: 'M5 12h14m-6-6 6 6-6 6', diagonal: 'M6 18 18 6M6 6h12v12', down: 'M12 4v16m-6-6 6 6 6-6',
  search: 'm21 21-5-5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0', menu: 'M4 6h16M4 12h16M4 18h16', close: 'm6 6 12 12M6 18 18 6',
  mail: 'M3 5h18v14H3zM3 5l9 8 9-8', copy: 'M8 8h13v13H8zM16 8V3H3v13h5', bookmark: 'M6 3h12v18l-6-4-6 4z',
  sun: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5',
  pause: 'M8 5v14M16 5v14', play: 'm8 4 12 8-12 8z', reset: 'M3 11a9 9 0 1 1 2 7M3 4v7h7', download: 'M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5', check: 'm4 12 5 5L20 6', leaf: 'M20 3C9 2 2 7 5 15c6 8 17 1 15-12ZM5 19l10-10', code: 'm8 5-7 7 7 7m8-14 7 7-7 7M14 3l-4 18'
}
</script>
<template><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" class="icon"><path :d="paths[name] || paths.arrow" /></svg></template>
