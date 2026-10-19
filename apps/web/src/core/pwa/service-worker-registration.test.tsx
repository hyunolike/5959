import { render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
  SERVICE_WORKER_URL,
  ServiceWorkerRegistration,
  shouldRegister,
} from "./service-worker-registration";

function stubServiceWorker() {
  const register = vi.fn(async () => ({}));
  Object.defineProperty(navigator, "serviceWorker", {
    configurable: true,
    value: { register },
  });
  return register;
}

afterEach(() => {
  Reflect.deleteProperty(navigator, "serviceWorker");
});

describe("ServiceWorkerRegistration", () => {
  it("COLDSTART-AC6 https 주소에서만 서비스 워커를 붙인다", () => {
    expect(shouldRegister("https://new.5959.site")).toBe(true);
    expect(shouldRegister("https://ogu-web-abc.run.app")).toBe(true);
    expect(shouldRegister("http://localhost:3000")).toBe(false);
  });

  it("COLDSTART-AC6 운영 주소에서는 /sw.js를 등록하고 화면에는 아무것도 그리지 않는다", () => {
    const register = stubServiceWorker();

    const { container } = render(
      <ServiceWorkerRegistration appOrigin="https://new.5959.site" />,
    );

    expect(register).toHaveBeenCalledWith(SERVICE_WORKER_URL);
    expect(container).toBeEmptyDOMElement();
  });

  it("COLDSTART-AC6 로컬과 e2e(http://localhost)에서는 등록하지 않는다", () => {
    const register = stubServiceWorker();

    render(<ServiceWorkerRegistration appOrigin="http://localhost:3000" />);

    expect(register).not.toHaveBeenCalled();
  });
});
