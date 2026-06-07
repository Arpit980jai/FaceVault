import 'dart:typed_data';

import 'package:facevault/facevault.dart';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await FaceVault.init(const FaceVaultConfig(matchThreshold: 0.60));
  runApp(const FaceVaultDemoApp());
}

/// Root widget of the FaceVault example app.
class FaceVaultDemoApp extends StatelessWidget {
  /// Creates the demo app.
  const FaceVaultDemoApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'FaceVault Demo',
      theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
      home: const HomePage(),
    );
  }
}

/// Demonstrates enrollment and the four search modes.
class HomePage extends StatefulWidget {
  /// Creates the home page.
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final ImagePicker _picker = ImagePicker();
  String _status = 'Ready. Enroll someone, then search.';

  Future<Uint8List?> _pickBytes() async {
    final XFile? file = await _picker.pickImage(source: ImageSource.gallery);
    return file?.readAsBytes();
  }

  Future<void> _enroll() async {
    final Uint8List? bytes = await _pickBytes();
    if (bytes == null) return;
    final String name = 'Person ${DateTime.now().millisecondsSinceEpoch % 1000}';
    try {
      final PersonRecord p =
          await FaceVault.enrollPerson(name: name, photos: <Uint8List>[bytes]);
      setState(() => _status = 'Enrolled ${p.name} (${p.personId})');
    } catch (e) {
      setState(() => _status = 'Enroll failed: $e');
    }
  }

  Future<void> _searchByPhoto() async {
    final Uint8List? bytes = await _pickBytes();
    if (bytes == null) return;
    try {
      final MatchResult r = await FaceVault.searchByPhoto(bytes);
      setState(() => _status = r.matched
          ? 'Match: ${r.person!.name} @ ${(r.confidence * 100).toStringAsFixed(1)}%'
          : 'No match (best ${(r.confidence * 100).toStringAsFixed(1)}%)');
    } catch (e) {
      setState(() => _status = 'Search failed: $e');
    }
  }

  Future<void> _findAll() async {
    final Uint8List? bytes = await _pickBytes();
    if (bytes == null) return;
    try {
      final List<MatchResult> results = await FaceVault.findAllInPhoto(bytes);
      final int matched = results.where((MatchResult r) => r.matched).length;
      setState(() =>
          _status = 'Found ${results.length} face(s), $matched matched.');
    } catch (e) {
      setState(() => _status = 'Find-all failed: $e');
    }
  }

  Future<void> _listPersons() async {
    final List<PersonRecord> people = await FaceVault.listAllPersons();
    setState(() => _status =
        'Enrolled (${people.length}): ${people.map((PersonRecord p) => p.name).join(', ')}');
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('FaceVault Demo')),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            FilledButton(onPressed: _enroll, child: const Text('Enroll Person')),
            const SizedBox(height: 8),
            FilledButton(
                onPressed: _searchByPhoto,
                child: const Text('Search by Photo')),
            const SizedBox(height: 8),
            FilledButton(
                onPressed: _findAll, child: const Text('Find All in Photo')),
            const SizedBox(height: 8),
            OutlinedButton(
                onPressed: _listPersons, child: const Text('List Enrolled')),
            const SizedBox(height: 24),
            Text(_status, style: Theme.of(context).textTheme.bodyLarge),
          ],
        ),
      ),
    );
  }
}
