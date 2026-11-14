import qrcode from 'qrcode-generator';
import {useMemo} from 'react';

/**
 * Renders a value as qr code in svg, drawn in the page so no image has to be loaded (the content security policy only allows own images).
 */
export function QrCode({value, size = 192, label}: { value: string; size?: number; label: string }) {
	const {count, path} = useMemo(() => {
		const qr = qrcode(0, 'M');
		qr.addData(value);
		qr.make();
		const modules = qr.getModuleCount();
		let d = '';
		for (let row = 0; row < modules; row++) {
			for (let col = 0; col < modules; col++) {
				if (qr.isDark(row, col)) {
					d += `M${col + 4},${row + 4}h1v1h-1z`;
				}
			}
		}
		return {count: modules + 8, path: d};
	}, [value]);
	// Always dark on white, scanners do not read inverted codes reliably
	return (
		<svg className="qr-code" width={size} height={size} viewBox={`0 0 ${count} ${count}`} role="img" aria-label={label} shapeRendering="crispEdges">
			<rect width={count} height={count} fill="#fff"/>
			<path d={path} fill="#000"/>
		</svg>
	);
}
