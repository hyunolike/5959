"use client";

import { useSupportResourcesQuery } from "../api/use-support-resources-query";

/** 목록을 받는 중이거나 못 받았을 때도 보이는 번호. */
const FALLBACK = { name: "자살예방상담전화", phone: "109" };

/**
 * 전화 링크. 가장 급할 때 누르는 곳이라 누르는 영역을 44px로 두고, 화면 낭독기의 링크 목록에서도
 * 어느 기관인지 알 수 있게 이름을 함께 읽힌다. 보이는 글자(번호)는 이름 안에 그대로 들어 있다.
 */
function PhoneLink({ name, phone }: { name: string; phone: string }) {
  return (
    <a
      href={`tel:${phone.replaceAll("-", "")}`}
      aria-label={`${name} ${phone} 전화 걸기`}
      className="text-fg -mx-2 inline-flex min-h-11 items-center px-2 font-bold underline underline-offset-2"
    >
      {phone}
    </a>
  );
}

/**
 * 도움받을 수 있는 곳(상담 전화) 목록(005 US1-AC1). 전화번호는 누르면 전화 앱으로 넘어간다.
 * 목록을 받는 중이거나 못 받아도 가장 중요한 번호 하나는 보인다. [enabled]가 false면 받지 않고
 * 아무것도 그리지 않는다.
 */
export function SupportResourceList({ enabled = true }: { enabled?: boolean }) {
  const { data: resources } = useSupportResourcesQuery({ enabled });

  if (!enabled) {
    return null;
  }
  if (resources) {
    return (
      <ul aria-label="도움받을 수 있는 곳" className="flex flex-col gap-2">
        {resources.map((resource) => (
          <li
            key={resource.phone}
            className="text-body-sm flex flex-wrap items-center gap-x-2"
          >
            <span className="text-fg font-medium">{resource.name}</span>
            <PhoneLink name={resource.name} phone={resource.phone} />
            <span className="text-label text-fg-subtle">
              {resource.hours} · {resource.description}
            </span>
          </li>
        ))}
      </ul>
    );
  }
  return (
    <p className="text-body-sm text-fg flex flex-wrap items-center gap-x-2">
      <span className="font-medium">{FALLBACK.name}</span>
      <PhoneLink {...FALLBACK} />
    </p>
  );
}
