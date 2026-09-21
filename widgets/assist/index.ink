<script setup>
export default {
  data: {
    battery: '--',
    mode: 'Ready',
    episodes: 0,
  },

  onAttach() {
    try {
      this.setData({
        episodes: Number(localStorage.getItem('sidekick.episodes') || 0),
      });
    } catch (e) { /* storage unavailable */ }
    navigator.getBattery()
      .then((b) => this.setData({ battery: Math.round(b.level * 100) + '%' }))
      .catch(() => {});
  },
};
</script>

<widget>
  <view class="wrap">
    <text class="title g-ok">Sidekick</text>
    <text class="g-mid">{{mode}}</text>
    <text class="g-dim">batt {{battery}} · mem {{episodes}}</text>
  </view>
</widget>

<style>
.wrap { padding: 8px; }
.title { font-size: 16px; font-weight: bold; }
</style>
