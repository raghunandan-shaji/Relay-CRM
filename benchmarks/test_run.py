import csv
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec=importlib.util.spec_from_file_location('benchmark_run',Path(__file__).with_name('run.py'))
run=importlib.util.module_from_spec(spec);spec.loader.exec_module(run)

class BenchmarkTests(unittest.TestCase):
    def test_statistics_exclude_warmup_and_include_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'samples.csv'
            with path.open('w',newline='') as output:
                writer=csv.DictWriter(output,fieldnames=['timeStamp','elapsed','success','label','responseCode','upstream']);writer.writeheader()
                for stamp,elapsed,success,status in [(0,1,'true','200'),(7000,20,'true','200'),(7100,80,'false','503')]:
                    writer.writerow(dict(timeStamp=stamp,elapsed=elapsed,success=success,label='profile',responseCode=status,upstream='app-1'))
            result=run.summarize(path,7)
            self.assertEqual(result['requests'],2);self.assertEqual(result['mean_ms'],50);self.assertEqual(result['p95_ms'],80)
            self.assertEqual(result['success_pct'],50);self.assertAlmostEqual(result['throughput_rps'],2/.18,places=3)
    def test_plan_has_six_authenticated_samplers_without_literal_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'test.jmx';run.generate_plan(path)
            root=ET.parse(path).getroot();self.assertEqual(len(root.findall('.//HTTPSamplerProxy')),6)
            self.assertEqual(len(root.findall('.//JSR223Assertion')),6)
            text=path.read_text();self.assertIn('${staffToken}',text);self.assertNotIn('benchmark-password',text)

    def test_resource_memory_units(self):
        import analyze
        self.assertEqual(analyze.memory_mb('1GiB'),1024)
        self.assertEqual(analyze.memory_mb('256MiB'),256)
        with self.assertRaises(ValueError):analyze.memory_mb('unknown')

if __name__=='__main__':unittest.main()
