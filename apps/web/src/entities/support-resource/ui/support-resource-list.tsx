"use client";

import { useSupportResourcesQuery } from "../api/use-support-resources-query";

/**
 * 도움받을 수 있는 곳(상담 전화) 목록(005 US1-AC1). 전화번호는 누르면 전화 앱으로 넘어간다.
 * 목록을 못 받아도 가장 중요한 번호 하나는 보인다. [enabled]가 false면 받지 않고 아무것도 그리지 않는다.
 */
export function SupportResourceList({ enabled = true }: { enabled?: boolean }) {
  const { data: resources, isError } = useSupportResourcesQuery({ enabled });

  if (!enabled) {
    return null;
  }
  if (resources) {
    return (
      <ul aria-label="도움받을 수 있는 곳" className="flex flex-col gap-2">
        {resources.map((resource) => (
          <li
            key={resource.phone}
            className="flex flex-wrap items-baseline gap-x-2 text-sm"
          >
            <span className="font-medium text-neutral-900">
              {resource.name}
            </span>
            <a
              href={`tel:${resource.phone.replaceAll("-", "")}`}
              className="font-semibold text-neutral-900 underline underline-offset-2 focus-visible:ring-2 focus-visible:ring-neutral-950 focus-visible:outline-none"
            >
              {resource.phone}
            </a>
            <span className="text-xs text-neutral-600">
              {resource.hours} · {resource.description}
            </span>
          </li>
        ))}
      </ul>
    );
  }
  return isError ? (
    <p className="text-sm text-neutral-900">
      자살예방상담전화{" "}
      <a href="tel:109" className="font-semibold underline">
        109
      </a>
    </p>
  ) : null;
}
