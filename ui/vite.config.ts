import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The ui is served by the server at /ui/, during development the api is proxied to a locally running server
const target = process.env.ARTIFACTORY_URL ?? 'http://localhost:8080';

export default defineConfig({
	base: '/ui/',
	plugins: [react()],
	build: {
		outDir: 'dist',
		emptyOutDir: true,
	},
	server: {
		proxy: {
			'/api': target,
			'/health': target,
			'/maven': target,
			'/generic': target,
			'/pypi': target,
			'/npm': target,
			'/nuget': target,
			'/cargo': target,
		},
	},
});
