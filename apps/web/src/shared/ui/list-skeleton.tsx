export function ListSkeleton({ label }: { label: string }) {
  return (
    <div role="status" aria-busy="true">
      <span className="sr-only">{label} 불러오는 중</span>
      <div aria-hidden className="divide-y divide-neutral-200">
        {[0, 1, 2].map((row) => (
          <div key={row} className="space-y-3 py-6">
            <div className="h-3 w-24 rounded bg-neutral-100" />
            <div className="h-4 w-4/5 rounded bg-neutral-100" />
            <div className="h-4 w-3/5 rounded bg-neutral-100" />
            <div className="h-3 w-16 rounded bg-neutral-100" />
          </div>
        ))}
      </div>
    </div>
  );
}
