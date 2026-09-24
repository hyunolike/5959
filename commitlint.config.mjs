export default {
  extends: ["@commitlint/config-conventional"],
  rules: {
    // config-conventional's subject-case check only recognizes Latin-script
    // words. A Korean subject that leads with (or contains) an English/
    // acronym token — e.g. "Next.js FSD 템플릿 이식과 ..." — gets
    // misclassified as sentence-case/upper-case and rejected even though it
    // is a normal, correctly formatted commit message. Commit subjects here
    // are written in Korean, so this rule is disabled rather than forcing
    // awkward all-lowercase acronyms.
    "subject-case": [0],
  },
};
