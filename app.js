export default {
  onLaunch() {
    this.globalData.launchedAt = Date.now();
  },
  onShow() {
    // Agent returned to foreground — perception loops resume here in later phases.
  },
  onHide() {
    // Suspend perception loops here in later phases.
  },
  globalData: {
    launchedAt: 0,
    agent: 'Sidekick',
    version: '0.1.0-phase1',
  },
};
