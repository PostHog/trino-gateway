#!/usr/bin/env python3
"""Run the isolated pooled HTTP contracts in a fixed order."""

import argparse
import os
import unittest


SUITES = {"pooled": ["test_pool_lifecycle", "test_graceful_shutdown", "test_whole_query_cancel"]}


def cases(suite):
    for case in suite:
        if isinstance(case, unittest.TestSuite):
            yield from cases(case)
        else:
            yield case


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", required=True, choices=SUITES)
    parser.add_argument("--list", action="store_true", help="List assertions without starting the fixture")
    args = parser.parse_args()
    suite = unittest.defaultTestLoader.loadTestsFromNames(SUITES[args.suite])
    if args.list:
        for case in cases(suite):
            print(case.id())
        print(f"{suite.countTestCases()} tests")
        return 0
    missing = [name for name in ("GATEWAY_TEST_CLASSPATH", "GATEWAY_TEST_JAVA", "GATEWAY_TEST_PG_BIN")
               if not os.environ.get(name)]
    if missing:
        parser.error("The isolated fixture requires " + ", ".join(missing))
    return 0 if unittest.TextTestRunner(verbosity=2, failfast=True).run(suite).wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
