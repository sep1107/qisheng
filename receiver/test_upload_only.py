import json
import unittest
from qisheng_upload_only import permitted

class UploadBoundaryTest(unittest.TestCase):
    def call(self, method, leaf, query=None, body=b''):
        return permitted(method, '/demo/' + leaf, query or {}, body, 'demo', {'f:mine', 'h:mine'})

    def test_remote_reads_and_deletes_are_denied(self):
        for leaf in ['_changes', '_all_docs', 'not-owned', '_bulk_get']:
            self.assertIsNone(self.call('GET', leaf))
        self.assertIsNone(self.call('GET', '_all_docs', {'keys': ['["not-owned"]']}))
        self.assertIsNone(self.call('DELETE', 'f:mine'))
        self.assertIsNone(self.call('GET', '_local/../not-owned'))

    def test_compatibility_sampling_uses_only_owned_ids(self):
        result = self.call('GET', '_all_docs', {'startkey': ['"f:"'], 'endkey': ['"f:zzzz"'], 'include_docs': ['true']})
        self.assertEqual({'keys': ['["f:mine"]']}, result[1])

    def test_only_owned_writes_and_metadata_are_allowed(self):
        self.assertIsNone(self.call('POST', '_bulk_docs', body=b'{"docs":[{"_id":"not-owned"}]}'))
        self.assertIsNone(self.call('POST', '_revs_diff', body=b'{"not-owned":["1-test"]}'))
        self.assertIsNotNone(self.call('POST', '_bulk_docs', body=b'{"docs":[{"_id":"f:mine"}]}'))
        self.assertIsNotNone(self.call('GET', '_local/checkpoint.ab=='))
        self.assertIsNotNone(self.call('GET', 'obsydian_livesync_version'))
