import importlib.util
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load(name: str, file: str):
    spec = importlib.util.spec_from_file_location(name, HERE / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


selection = load("layer_selection", "layer-selection.py")
combine = load("combine_coverage", "combine-coverage.py")
MATRIX = ["1.2.5-forge", "1.20.6-forge", "a1.1.2_01-ornithe", "b1.7.3-ornithe", "b1.7.3-stationapi"]


class LayerSelectionTest(unittest.TestCase):
    def test_loader_set_matches_combine_coverage(self):
        self.assertEqual(set(selection.LAYER_SERVER_LOADERS), set(combine.ORNITHE_LOADERS))

    def test_pull_request_builds_only_ornithe_layers_whose_leg_runs(self):
        self.assertEqual(
            selection.select("pull_request", MATRIX, ["a1.1.2_01-ornithe", "1.20.6-forge"]),
            ["a1.1.2_01-ornithe"],
        )

    def test_pull_request_without_a_leg_builds_nothing(self):
        self.assertEqual(selection.select("pull_request", MATRIX, []), [])

    def test_pull_request_with_every_leg_builds_both_ornithe_layers(self):
        self.assertEqual(
            selection.select("pull_request", MATRIX, MATRIX),
            ["a1.1.2_01-ornithe", "b1.7.3-ornithe"],
        )

    def test_non_ornithe_leg_builds_nothing(self):
        self.assertEqual(selection.select("pull_request", MATRIX, ["b1.7.3-stationapi"]), [])

    def test_push_and_dispatch_build_every_layer(self):
        for event in ("push", "workflow_dispatch", "schedule"):
            self.assertEqual(selection.select(event, MATRIX, []), MATRIX)


class WorkflowTest(unittest.TestCase):
    """The workflow wires the selection to the right job (a slip once moved it to core-release)."""

    @classmethod
    def setUpClass(cls):
        import yaml

        path = HERE.parent / "workflows" / "build.yml"
        cls.jobs = yaml.safe_load(path.read_text())["jobs"]

    def test_layer_job_runs_on_pull_request_by_the_layer_output(self):
        job = self.jobs["layer"]
        self.assertIn("needs.discover.outputs.layer != '[]'", job["if"])
        self.assertNotIn("pull_request", job["if"])
        self.assertIn("needs.discover.outputs.layer", job["strategy"]["matrix"]["dir"])

    def test_jobs_that_build_for_the_jar_stay_off_pull_requests(self):
        for name in ("core-release", "universal"):
            self.assertIn("!= 'pull_request'", self.jobs[name]["if"], name)


if __name__ == "__main__":
    unittest.main()
