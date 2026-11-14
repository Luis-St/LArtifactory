import {useLayoutEffect, useMemo, useRef, useState} from 'react';
import {formatNumber} from '../format';
import {type ActivityBucket, REPOSITORY_TYPES, type RepositoryType} from '../types';
import {TYPE_INFO} from './ui';

const DAYS = 30;
const HEIGHT = 140;
const AXIS = 28;
const BOTTOM = 22;

interface Day {
	date: Date;
	total: number;
	byType: Partial<Record<RepositoryType, number>>;
}

function dayKey(date: Date): string {
	return `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;
}

/**
 * Publications per local day over the last 30 days: package versions, and files for generic repositories.
 */
function collectDays(activity: ActivityBucket[]): Day[] {
	const today = new Date();
	today.setHours(0, 0, 0, 0);
	const days: Day[] = Array.from({length: DAYS}, (_, index) => {
		const date = new Date(today);
		date.setDate(today.getDate() - (DAYS - 1 - index));
		return {date, total: 0, byType: {}};
	});
	const byKey = new Map(days.map(day => [dayKey(day.date), day]));
	// The server counts per hour, which places every publication on its local day for whole hour time zones
	for (const bucket of activity) {
		const day = byKey.get(dayKey(new Date(bucket.hour)));
		if (day) {
			for (const [type, count] of Object.entries(bucket.counts) as [RepositoryType, number][]) {
				day.total += count;
				day.byType[type] = (day.byType[type] ?? 0) + count;
			}
		}
	}
	return days;
}

/**
 * A rounded step above the maximum with two or three gridlines, so the axis reads 0, 5, 10 instead of 0, 3.5, 7.
 */
function niceMax(value: number): { max: number; ticks: number[] } {
	if (value <= 4) {
		const max = Math.max(1, value);
		return {max, ticks: max === 1 ? [0, 1] : [0, Math.ceil(max / 2), max]};
	}
	const magnitude = 10 ** Math.floor(Math.log10(value));
	const step = [1, 2, 2.5, 5, 10].map(factor => factor * magnitude).find(candidate => value / candidate <= 3) ?? magnitude * 10;
	const max = Math.ceil(value / step) * step;
	return {max, ticks: Array.from({length: Math.round(max / step) + 1}, (_, index) => index * step)};
}

const dateFormat = new Intl.DateTimeFormat(undefined, {month: 'short', day: 'numeric'});
const weekdayFormat = new Intl.DateTimeFormat(undefined, {weekday: 'short', month: 'short', day: 'numeric'});

export function ActivityChart({activity}: { activity: ActivityBucket[] }) {
	const container = useRef<HTMLDivElement>(null);
	const [width, setWidth] = useState(0);
	const [hover, setHover] = useState<number | null>(null);
	const days = useMemo(() => collectDays(activity), [activity]);
	const total = days.reduce((sum, day) => sum + day.total, 0);
	const {max, ticks} = niceMax(Math.max(...days.map(day => day.total)));
	
	useLayoutEffect(() => {
		const element = container.current;
		if (!element) {
			return;
		}
		const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width));
		observer.observe(element);
		return () => observer.disconnect();
	}, []);
	
	const plotWidth = Math.max(0, width - AXIS);
	const slot = plotWidth / DAYS;
	// 2px surface gap between neighbouring bars
	const barWidth = Math.max(2, slot - 2);
	const y = (value: number) => (HEIGHT - BOTTOM) * (1 - value / max);
	const hovered = hover !== null ? days[hover] : null;
	
	return (
		<div className="activity">
			<div className="activity-head">
				<div>
					<span className="activity-total">{formatNumber(total)}</span>
					<span className="muted small"> publications in the last {DAYS} days</span>
				</div>
			</div>
			<div className="activity-plot" ref={container} onMouseLeave={() => setHover(null)}>
				{width > 0 && (
					<svg width={width} height={HEIGHT} role="img" aria-label={`Publications per day over the last ${DAYS} days, ${total} in total`}>
						{ticks.map(tick => (
							<g key={tick}>
								<line className="activity-grid" x1={AXIS} x2={width} y1={y(tick)} y2={y(tick)}/>
								<text className="activity-axis" x={AXIS - 8} y={y(tick)} dy="0.32em" textAnchor="end">{tick}</text>
							</g>
						))}
						{days.map((day, index) => {
							const x = AXIS + index * slot + (slot - barWidth) / 2;
							const top = y(day.total);
							const height = HEIGHT - BOTTOM - top;
							const radius = Math.min(4, barWidth / 2, height);
							return (
								<g key={index}>
									{day.total > 0 && (
										// Rounded at the data end, square at the baseline
										<path className={`activity-bar${hover === index ? ' activity-bar-hover' : ''}`}
											  d={`M${x},${HEIGHT - BOTTOM} V${top + radius} Q${x},${top} ${x + radius},${top} H${x + barWidth - radius} Q${x + barWidth},${top} ${x + barWidth},${top + radius} V${HEIGHT - BOTTOM} Z`}/>
									)}
									{/* Hit target spans the whole slot and height, larger than the bar */}
									<rect x={AXIS + index * slot} y={0} width={slot} height={HEIGHT - BOTTOM} fill="transparent" onMouseEnter={() => setHover(index)}/>
								</g>
							);
						})}
						{[0, Math.floor(DAYS / 2), DAYS - 1].map(index => (
							<text key={index} className="activity-axis" x={AXIS + index * slot + slot / 2} y={HEIGHT - 6}
								  textAnchor={index === 0 ? 'start' : index === DAYS - 1 ? 'end' : 'middle'}>
								{index === DAYS - 1 ? 'Today' : dateFormat.format(days[index].date)}
							</text>
						))}
					</svg>
				)}
				{hovered && hover !== null && (
					<div className="chart-tooltip" style={{left: Math.min(Math.max(AXIS + hover * slot + slot / 2, 90), width - 90), top: Math.max(0, y(hovered.total) - 8)}}>
						<strong>{weekdayFormat.format(hovered.date)}</strong>
						<span>{hovered.total === 0 ? 'Nothing published' : `${formatNumber(hovered.total)} ${hovered.total === 1 ? 'publication' : 'publications'}`}</span>
						{REPOSITORY_TYPES.filter(type => hovered.byType[type]).map(type => (
							<span key={type} className="chart-tooltip-row">
								<span className="dot" style={{background: TYPE_INFO[type].color}}/>
								<span>{TYPE_INFO[type].label}</span>
								<span className="chart-tooltip-value">{hovered.byType[type]}</span>
							</span>
						))}
					</div>
				)}
			</div>
			{/* Hidden through a wrapper, browsers do not clip a table caption with overflow on the table itself */}
			<div className="sr-only">
				<table>
					<caption>Publications per day</caption>
					<thead>
					<tr>
						<th>Day</th>
						<th>Publications</th>
					</tr>
					</thead>
					<tbody>
					{days.map(day => (
						<tr key={dayKey(day.date)}>
							<td>{weekdayFormat.format(day.date)}</td>
							<td>{day.total}</td>
						</tr>
					))}
					</tbody>
				</table>
			</div>
		</div>
	);
}
