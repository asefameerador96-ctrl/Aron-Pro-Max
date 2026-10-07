// Wall-clock reads live here so components stay pure (react-hooks/purity) and tests can pass a fixed time.
export const serverNowMs = (): number => Date.now();
