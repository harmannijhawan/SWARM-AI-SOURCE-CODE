import { build } from 'esbuild';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const common = {
  bundle: true,
  platform: 'node',
  target: 'node22',
  format: 'cjs',
  sourcemap: true,
  logLevel: 'info',
  external: ['electron', 'playwright-core', 'node:sqlite'],
};

await Promise.all([
  build({ ...common, entryPoints: [path.join(root, 'electron/main.ts')], outfile: path.join(root, 'dist/main/main.js') }),
  build({ ...common, entryPoints: [path.join(root, 'electron/preload.ts')], outfile: path.join(root, 'dist/main/preload.js') }),
]);
