import { useId, type ComponentProps } from "react";

import { Input } from "./input";
import { Label } from "./label";

/** 이름표, 입력, 도움말, 오류를 한 묶음으로 잇는다. 오류는 `role="alert"`로 바로 읽힌다. */
export function TextField({
  label,
  hint,
  error,
  id,
  ...props
}: Omit<ComponentProps<"input">, "aria-invalid" | "aria-describedby"> & {
  label: string;
  hint?: string;
  error?: string | null;
}) {
  const autoId = useId();
  const fieldId = id ?? autoId;
  const hintId = `${fieldId}-hint`;
  const errorId = `${fieldId}-error`;
  const describedBy =
    [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(" ") ||
    undefined;

  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={fieldId}>{label}</Label>
      <Input
        id={fieldId}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        {...props}
      />
      {hint ? (
        <p id={hintId} className="text-label text-fg-subtle">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={errorId} role="alert" className="text-label text-danger">
          {error}
        </p>
      ) : null}
    </div>
  );
}
