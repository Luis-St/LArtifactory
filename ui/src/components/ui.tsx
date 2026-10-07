import { useEffect, useRef, useState, type ButtonHTMLAttributes, type ReactNode } from 'react';
import type { RepositoryType } from '../types';

export const TYPE_INFO: Record<RepositoryType, { label: string; ecosystem: string; color: string }> = {
	maven: { label: 'Maven', ecosystem: 'Maven & Gradle', color: 'var(--type-maven)' },
	generic: { label: 'Generic', ecosystem: 'Arbitrary files', color: 'var(--type-generic)' },
	pypi: { label: 'PyPI', ecosystem: 'pip & twine', color: 'var(--type-pypi)' },
	npm: { label: 'npm', ecosystem: 'npm, yarn & pnpm', color: 'var(--type-npm)' },
	nuget: { label: 'NuGet', ecosystem: 'dotnet & nuget', color: 'var(--type-nuget)' },
	cargo: { label: 'Cargo', ecosystem: 'Rust crates', color: 'var(--type-cargo)' },
};

export function TypeBadge({ type }: { type: RepositoryType }) {
	return <span className="type-badge" style={{ '--badge': TYPE_INFO[type]?.color } as React.CSSProperties}>{TYPE_INFO[type]?.label ?? type}</span>;
}

export function TypeIcon({ type, size = 36 }: { type: RepositoryType; size?: number }) {
	return (
		<span className="type-icon" style={{ '--badge': TYPE_INFO[type]?.color, width: size, height: size } as React.CSSProperties} aria-hidden>
			{(TYPE_INFO[type]?.label ?? type).substring(0, 2)}
		</span>
	);
}

export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'neutral' | 'success' | 'warning' | 'danger' | 'accent' }) {
	return <span className={`badge badge-${tone}`}>{children}</span>;
}

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'secondary' | 'danger' | 'ghost'; small?: boolean };

export function Button({ variant = 'secondary', small, className, ...props }: ButtonProps) {
	return <button type="button" className={`btn btn-${variant}${small ? ' btn-small' : ''}${className ? ' ' + className : ''}`} {...props} />;
}

export function Spinner({ label = 'Loading' }: { label?: string }) {
	return (
		<div className="spinner-wrap" role="status">
			<span className="spinner" aria-hidden />
			<span>{label}…</span>
		</div>
	);
}

export function ErrorBox({ error, onRetry }: { error: Error | string; onRetry?: () => void }) {
	return (
		<div className="error-box" role="alert">
			<span>{typeof error === 'string' ? error : error.message}</span>
			{onRetry && <Button small onClick={onRetry}>Retry</Button>}
		</div>
	);
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
	return (
		<div className="empty">
			<strong>{title}</strong>
			{children && <div>{children}</div>}
		</div>
	);
}

export function PageHeader({ title, subtitle, actions }: { title: ReactNode; subtitle?: ReactNode; actions?: ReactNode }) {
	return (
		<header className="page-header">
			<div>
				<h1>{title}</h1>
				{subtitle && <p className="muted">{subtitle}</p>}
			</div>
			{actions && <div className="page-actions">{actions}</div>}
		</header>
	);
}

export function Card({ title, actions, children, className }: { title?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string }) {
	return (
		<section className={`card${className ? ' ' + className : ''}`}>
			{(title || actions) && (
				<div className="card-header">
					{title && <h2>{title}</h2>}
					{actions}
				</div>
			)}
			{children}
		</section>
	);
}

export function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: ReactNode }) {
	return (
		<div className="stat">
			<span className="stat-label">{label}</span>
			<span className="stat-value">{value}</span>
			{hint && <span className="stat-hint">{hint}</span>}
		</div>
	);
}

export function Modal({ title, onClose, children, footer, wide }: { title: string; onClose: () => void; children: ReactNode; footer?: ReactNode; wide?: boolean }) {
	const dialog = useRef<HTMLDialogElement>(null);
	useEffect(() => {
		dialog.current?.showModal();
	}, []);
	return (
		<dialog ref={dialog} className={`modal${wide ? ' modal-wide' : ''}`} onCancel={event => { event.preventDefault(); onClose(); }}>
			<div className="modal-header">
				<h2>{title}</h2>
				<button type="button" className="icon-btn" onClick={onClose} aria-label="Close">×</button>
			</div>
			<div className="modal-body">{children}</div>
			{footer && <div className="modal-footer">{footer}</div>}
		</dialog>
	);
}

export function ConfirmDialog({ title, message, confirmLabel = 'Delete', onConfirm, onClose }: {
	title: string;
	message: ReactNode;
	confirmLabel?: string;
	onConfirm: () => Promise<void>;
	onClose: () => void;
}) {
	const [busy, setBusy] = useState(false);
	const [error, setError] = useState<string>();
	const confirm = async () => {
		setBusy(true);
		setError(undefined);
		try {
			await onConfirm();
			onClose();
		} catch (e) {
			setError(e instanceof Error ? e.message : String(e));
			setBusy(false);
		}
	};
	return (
		<Modal title={title} onClose={onClose} footer={
			<>
				<Button onClick={onClose} disabled={busy}>Cancel</Button>
				<Button variant="danger" onClick={confirm} disabled={busy}>{busy ? 'Working…' : confirmLabel}</Button>
			</>
		}>
			<div className="confirm-message">{message}</div>
			{error && <ErrorBox error={error} />}
		</Modal>
	);
}

export function CopyButton({ value, label = 'Copy' }: { value: string; label?: string }) {
	const [copied, setCopied] = useState(false);
	const copy = async () => {
		try {
			await navigator.clipboard.writeText(value);
			setCopied(true);
			setTimeout(() => setCopied(false), 1500);
		} catch {
			// Clipboard access denied, the value stays selectable
		}
	};
	return <Button small variant="ghost" onClick={copy}>{copied ? 'Copied' : label}</Button>;
}

export function CodeBlock({ title, code }: { title?: string; code: string }) {
	return (
		<div className="code-block">
			<div className="code-block-header">
				<span>{title}</span>
				<CopyButton value={code} />
			</div>
			<pre><code>{code}</code></pre>
		</div>
	);
}

export function Toggle({ checked, onChange, label, description, disabled }: { checked: boolean; onChange: (value: boolean) => void; label: string; description?: string; disabled?: boolean }) {
	return (
		<label className="toggle">
			<input type="checkbox" checked={checked} onChange={event => onChange(event.target.checked)} disabled={disabled} />
			<span className="toggle-track" aria-hidden><span className="toggle-thumb" /></span>
			<span className="toggle-text">
				<span>{label}</span>
				{description && <small className="muted">{description}</small>}
			</span>
		</label>
	);
}

export function Field({ label, hint, children }: { label: string; hint?: ReactNode; children: ReactNode }) {
	return (
		<label className="field">
			<span className="field-label">{label}</span>
			{children}
			{hint && <small className="muted">{hint}</small>}
		</label>
	);
}

export function Tabs<T extends string>({ tabs, active, onChange }: { tabs: { id: T; label: ReactNode }[]; active: T; onChange: (id: T) => void }) {
	return (
		<div className="tabs" role="tablist">
			{tabs.map(tab => (
				<button key={tab.id} type="button" role="tab" aria-selected={tab.id === active} className={`tab${tab.id === active ? ' tab-active' : ''}`} onClick={() => onChange(tab.id)}>
					{tab.label}
				</button>
			))}
		</div>
	);
}

export function errorText(error: unknown): string {
	return error instanceof Error ? error.message : String(error);
}
